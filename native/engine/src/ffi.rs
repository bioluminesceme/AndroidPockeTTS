use crate::config::{Config, Origin};
use crate::model::{CpuBackend, LoadOptions, TtsModel, VoiceState};
use anyhow::{Context, Result, anyhow, bail};
use serde::Deserialize;
use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{CStr, CString, c_char, c_void};
use std::fs;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, Once, OnceLock, TryLockError};

type Float32Backend = xn::Unquantized<f32, xn::CpuDevice>;
type Int8Backend = xn::quantized::Q80F32;
pub type PocketAudioCallback = unsafe extern "C" fn(*const f32, usize, u64, *mut c_void) -> i32;

const STATUS_SUCCESS: i32 = 0;
const STATUS_CANCELLED: i32 = 1;
const STATUS_ERROR: i32 = -1;
const MAX_CALLBACK_SAMPLES: usize = 4096;

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct Settings {
    temperature: f32,
    sampler_decode_steps: usize,
    eos_threshold: f32,
    max_tokens: usize,
    frames_after_eos: RequiredNullableUsize,
    quantize: bool,
}

#[derive(Debug)]
struct RequiredNullableUsize(Option<usize>);

impl<'de> Deserialize<'de> for RequiredNullableUsize {
    fn deserialize<D>(deserializer: D) -> std::result::Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        Option::<usize>::deserialize(deserializer).map(Self)
    }
}

impl Settings {
    fn parse(json: &str) -> Result<Self> {
        let value: serde_json::Value =
            serde_json::from_str(json).context("invalid settings JSON")?;
        let object = value
            .as_object()
            .context("settings JSON must be an object")?;
        for field in [
            "temperature",
            "sampler_decode_steps",
            "eos_threshold",
            "max_tokens",
            "frames_after_eos",
            "quantize",
        ] {
            if !object.contains_key(field) {
                bail!("settings JSON is missing {field}")
            }
        }
        let settings: Self = serde_json::from_str(json).context("invalid settings JSON")?;
        if !settings.temperature.is_finite() || settings.temperature < 0.0 {
            bail!("temperature must be finite and nonnegative")
        }
        if !settings.eos_threshold.is_finite() {
            bail!("eos_threshold must be finite")
        }
        if settings.sampler_decode_steps == 0 {
            bail!("sampler_decode_steps must be positive")
        }
        if settings.max_tokens == 0 {
            bail!("max_tokens must be positive")
        }
        Ok(settings)
    }
}

struct Engine<B: CpuBackend> {
    model: TtsModel<B>,
    voice: Option<VoiceState<B>>,
}

/// quantize=false selects float32 weights. quantize=true selects int8 weights.
enum Engines {
    Float32(Engine<Float32Backend>),
    Int8(Engine<Int8Backend>),
}

/// Runs `$body` with `$engine` bound to the float32 or the int8 engine.
macro_rules! with_engine {
    ($engines:expr, $engine:ident => $body:expr) => {
        match $engines {
            Engines::Float32($engine) => $body,
            Engines::Int8($engine) => $body,
        }
    };
}

struct Instance {
    engines: Engines,
    max_tokens: usize,
    frames_after_eos: Option<usize>,
}

struct HandleState {
    instance: Mutex<Instance>,
    stop: AtomicBool,
    alive: AtomicBool,
}

static REGISTRY: OnceLock<Mutex<HashMap<u64, Arc<HandleState>>>> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn registry() -> &'static Mutex<HashMap<u64, Arc<HandleState>>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_handle() -> Result<u64> {
    NEXT_HANDLE
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |current| {
            current.checked_add(1)
        })
        .map_err(|_| anyhow!("handle space is exhausted"))
}

fn lock_registry() -> MutexGuard<'static, HashMap<u64, Arc<HandleState>>> {
    registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn set_last_error(message: impl AsRef<str>) {
    let clean = message.as_ref().replace('\0', "\\0");
    let value = CString::new(clean).unwrap_or_default();
    LAST_ERROR.with(|slot| *slot.borrow_mut() = value);
}

fn clear_last_error() {
    LAST_ERROR.with(|slot| *slot.borrow_mut() = CString::default());
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(message) = payload.downcast_ref::<&str>() {
        (*message).to_string()
    } else if let Some(message) = payload.downcast_ref::<String>() {
        message.clone()
    } else {
        "unknown Rust panic".to_string()
    }
}

fn ffi_status(operation: impl FnOnce() -> Result<i32>) -> i32 {
    clear_last_error();
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(status)) => status,
        Ok(Err(error)) => {
            set_last_error(format!("{error:#}"));
            STATUS_ERROR
        }
        Err(payload) => {
            set_last_error(format!("Rust panic: {}", panic_message(payload)));
            STATUS_ERROR
        }
    }
}

fn ffi_value<T: Copy>(error_value: T, operation: impl FnOnce() -> Result<T>) -> T {
    clear_last_error();
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => {
            set_last_error(format!("{error:#}"));
            error_value
        }
        Err(payload) => {
            set_last_error(format!("Rust panic: {}", panic_message(payload)));
            error_value
        }
    }
}

unsafe fn required_utf8<'a>(value: *const c_char, name: &str) -> Result<&'a str> {
    if value.is_null() {
        bail!("{name} is null")
    }
    // SAFETY: The C interface requires a readable NUL-terminated string for this call.
    let c_string = unsafe { CStr::from_ptr(value) };
    c_string
        .to_str()
        .with_context(|| format!("{name} is not valid UTF-8"))
}

fn local_file(path: &str, name: &str) -> Result<PathBuf> {
    if crate::download::is_remote_location(path) {
        bail!("{name} must be a local file")
    }
    let path = PathBuf::from(path);
    if !path.is_file() {
        bail!("{name} does not exist or is not a file: {}", path.display())
    }
    path.canonicalize()
        .with_context(|| format!("cannot resolve {name}: {}", path.display()))
}

fn resolve_config_path(value: &mut String, base: &Path, name: &str) -> Result<()> {
    if crate::download::is_remote_location(value) {
        bail!("{name} must be a local file")
    }
    let source = Path::new(value);
    let joined = if source.is_absolute() {
        source.to_path_buf()
    } else {
        base.join(source)
    };
    if !joined.is_file() {
        bail!(
            "{name} does not exist or is not a file: {}",
            joined.display()
        )
    }
    let resolved = joined
        .canonicalize()
        .with_context(|| format!("cannot resolve {name}: {}", joined.display()))?;
    *value = resolved
        .to_str()
        .with_context(|| format!("{name} is not valid UTF-8: {}", resolved.display()))?
        .to_owned();
    if name != "flow_lm.lookup_table.tokenizer_path" {
        crate::tensor_validation::validate_file(Path::new(value))?;
    }
    Ok(())
}

fn load_config(config_path: &str) -> Result<(Config, PathBuf)> {
    let path = local_file(config_path, "config_path")?;
    let extension = path
        .extension()
        .and_then(|value| value.to_str())
        .unwrap_or_default();
    if !extension.eq_ignore_ascii_case("yaml") && !extension.eq_ignore_ascii_case("yml") {
        bail!("config_path must end with .yaml or .yml")
    }
    let yaml = fs::read_to_string(&path)
        .with_context(|| format!("cannot read config file {}", path.display()))?;
    let mut config = Config::parse(&yaml).context("invalid config")?;
    if config.weights_path_without_voice_cloning.is_some() {
        bail!("weights_path_without_voice_cloning fallback is not supported")
    }
    let base = path
        .parent()
        .context("config file has no parent directory")?;
    resolve_config_path(
        &mut config.flow_lm.lookup_table.tokenizer_path,
        base,
        "flow_lm.lookup_table.tokenizer_path",
    )?;
    if let Some(value) = config.flow_lm.weights_path.as_mut() {
        resolve_config_path(value, base, "flow_lm.weights_path")?;
    }
    if let Some(value) = config.mimi.weights_path.as_mut() {
        resolve_config_path(value, base, "mimi.weights_path")?;
    }
    if let Some(value) = config.weights_path.as_mut() {
        resolve_config_path(value, base, "weights_path")?;
    }
    Ok((config, path))
}

fn get_handle(handle: u64) -> Result<Arc<HandleState>> {
    if handle == 0 {
        bail!("invalid handle: 0")
    }
    lock_registry()
        .get(&handle)
        .cloned()
        .with_context(|| format!("invalid handle: {handle}"))
}

fn try_instance(handle: &HandleState) -> Result<MutexGuard<'_, Instance>> {
    match handle.instance.try_lock() {
        Ok(instance) => {
            if !handle.alive.load(Ordering::Acquire) {
                bail!("handle was destroyed")
            }
            Ok(instance)
        }
        Err(TryLockError::WouldBlock) => bail!("handle is busy"),
        Err(TryLockError::Poisoned(_)) => bail!("handle state is poisoned after a Rust panic"),
    }
}

struct NativeLogger;

impl log::Log for NativeLogger {
    fn enabled(&self, metadata: &log::Metadata<'_>) -> bool {
        metadata.level() <= log::Level::Info
    }

    fn log(&self, record: &log::Record<'_>) {
        if self.enabled(record.metadata()) {
            eprintln!("pocket_ffi {}: {}", record.level(), record.args());
        }
    }

    fn flush(&self) {}
}

static LOGGER: NativeLogger = NativeLogger;
static LOGGER_INIT: Once = Once::new();

fn initialize_logger() {
    LOGGER_INIT.call_once(|| {
        if log::set_logger(&LOGGER).is_ok() {
            log::set_max_level(log::LevelFilter::Info);
        }
    });
}

fn deliver_audio(
    samples: &[f32],
    stop: &AtomicBool,
    callback_status: &AtomicI32,
    first_sample: &AtomicU64,
    callback: PocketAudioCallback,
    user_address: usize,
) -> Result<()> {
    for block in samples.chunks(MAX_CALLBACK_SAMPLES) {
        if stop.load(Ordering::Acquire) {
            callback_status.store(STATUS_CANCELLED, Ordering::Release);
            bail!("generation was stopped")
        }
        let offset = first_sample.load(Ordering::Relaxed);
        // SAFETY: The callback and user pointer are borrowed for this synchronous call only.
        let result = unsafe {
            callback(
                block.as_ptr(),
                block.len(),
                offset,
                user_address as *mut c_void,
            )
        };
        match result {
            0 => {
                let length = u64::try_from(block.len()).context("audio chunk is too large")?;
                let next = offset
                    .checked_add(length)
                    .context("audio offset overflow")?;
                first_sample.store(next, Ordering::Relaxed);
            }
            1 => {
                callback_status.store(STATUS_CANCELLED, Ordering::Release);
                stop.store(true, Ordering::Release);
                bail!("audio callback cancelled generation")
            }
            code => {
                callback_status.store(code, Ordering::Release);
                stop.store(true, Ordering::Release);
                bail!("audio callback returned error {code}")
            }
        }
    }
    Ok(())
}

fn generation_status(
    callback_result: i32,
    stopped: bool,
    generation_result: Result<()>,
) -> Result<i32> {
    if callback_result == STATUS_CANCELLED {
        return Ok(STATUS_CANCELLED);
    }
    if callback_result != STATUS_SUCCESS {
        bail!("audio callback returned error {callback_result}")
    }
    if stopped {
        return Ok(STATUS_CANCELLED);
    }
    generation_result?;
    Ok(STATUS_SUCCESS)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn pocket_create(
    config_path: *const c_char,
    settings_json: *const c_char,
) -> u64 {
    ffi_value(0, || {
        initialize_logger();
        // SAFETY: Pointer validation is performed by required_utf8.
        let config_path = unsafe { required_utf8(config_path, "config_path") }?;
        // SAFETY: Pointer validation is performed by required_utf8.
        let settings_json = unsafe { required_utf8(settings_json, "settings_json") }?;
        let settings = Settings::parse(settings_json)?;
        let (config, resolved_config_path) = load_config(config_path)?;
        let options = LoadOptions {
            temperature: Some(settings.temperature),
            sampler_decode_steps: settings.sampler_decode_steps,
            noise_clamp: None,
            eos_threshold: Some(settings.eos_threshold),
        };
        let origin = Origin::Custom(
            resolved_config_path
                .to_str()
                .context("resolved config_path is not valid UTF-8")?
                .to_owned(),
        );
        let engines = if settings.quantize {
            Engines::Int8(Engine { model: TtsModel::load(config, origin, &options)?, voice: None })
        } else {
            Engines::Float32(Engine { model: TtsModel::load(config, origin, &options)?, voice: None })
        };
        let state = Arc::new(HandleState {
            instance: Mutex::new(Instance {
                engines,
                max_tokens: settings.max_tokens,
                frames_after_eos: settings.frames_after_eos.0,
            }),
            stop: AtomicBool::new(false),
            alive: AtomicBool::new(true),
        });
        let handle = next_handle()?;
        lock_registry().insert(handle, state);
        Ok(handle)
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn pocket_select_voice(handle: u64, speaker_path: *const c_char) -> i32 {
    ffi_status(|| {
        // SAFETY: Pointer validation is performed by required_utf8.
        let speaker_path = unsafe { required_utf8(speaker_path, "speaker_path") }?;
        let speaker_path = local_file(speaker_path, "speaker_path")?;
        if !speaker_path
            .extension()
            .and_then(|value| value.to_str())
            .is_some_and(|value| value == "safetensors")
        {
            bail!("speaker_path must end with .safetensors")
        }
        crate::tensor_validation::validate_file(&speaker_path)?;
        let state = get_handle(handle)?;
        let mut instance = try_instance(&state)?;
        let source = speaker_path
            .to_str()
            .context("resolved speaker_path is not valid UTF-8")?;
        with_engine!(&mut instance.engines, engine => {
            let voice = engine.model.get_state_for_audio_prompt(source, false)?;
            engine.voice = Some(voice);
        });
        Ok(STATUS_SUCCESS)
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn pocket_generate(
    handle: u64,
    text: *const c_char,
    callback: Option<PocketAudioCallback>,
    user: *mut c_void,
) -> i32 {
    ffi_status(|| {
        // SAFETY: Pointer validation is performed by required_utf8.
        let text = unsafe { required_utf8(text, "text") }?;
        let callback = callback.context("callback is null")?;
        let state = get_handle(handle)?;
        let instance = try_instance(&state)?;
        with_engine!(&instance.engines, engine => {
            engine.voice.as_ref().context("no voice is selected")?;
        });
        state.stop.store(false, Ordering::Release);
        let callback_status = AtomicI32::new(STATUS_SUCCESS);
        let first_sample = AtomicU64::new(0);
        let user_address = user as usize;
        let mut on_chunk = |samples: &[f32]| -> Result<()> {
            deliver_audio(
                samples,
                &state.stop,
                &callback_status,
                &first_sample,
                callback,
                user_address,
            )
        };
        let result = with_engine!(&instance.engines, engine => {
            let voice = engine.voice.as_ref().context("no voice is selected")?;
            engine.model.generate_audio_stream(
                voice,
                text,
                instance.max_tokens,
                instance.frames_after_eos,
                &state.stop,
                &mut on_chunk,
            )
        });
        generation_status(
            callback_status.load(Ordering::Acquire),
            state.stop.load(Ordering::Acquire),
            result,
        )
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn pocket_stop(handle: u64) -> i32 {
    ffi_status(|| {
        let state = get_handle(handle)?;
        if !state.alive.load(Ordering::Acquire) {
            bail!("handle was destroyed")
        }
        state.stop.store(true, Ordering::Release);
        Ok(STATUS_SUCCESS)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn pocket_destroy(handle: u64) -> i32 {
    ffi_status(|| {
        if handle == 0 {
            bail!("invalid handle: 0")
        }
        let mut entries = lock_registry();
        let state = entries
            .get(&handle)
            .cloned()
            .with_context(|| format!("invalid handle: {handle}"))?;
        let instance = match state.instance.try_lock() {
            Ok(instance) => instance,
            Err(TryLockError::WouldBlock) => bail!("handle is busy"),
            Err(TryLockError::Poisoned(poisoned)) => poisoned.into_inner(),
        };
        state.alive.store(false, Ordering::Release);
        entries.remove(&handle);
        drop(instance);
        Ok(STATUS_SUCCESS)
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn pocket_sample_rate(handle: u64) -> u32 {
    ffi_value(0, || {
        let state = get_handle(handle)?;
        let instance = try_instance(&state)?;
        let rate = with_engine!(&instance.engines, engine => engine.model.sample_rate());
        u32::try_from(rate).context("sample rate does not fit in uint32_t")
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn pocket_last_error() -> *const c_char {
    LAST_ERROR.with(|slot| slot.borrow().as_ptr())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct CallbackRecord {
        calls: Vec<(u64, usize)>,
        result: i32,
    }

    unsafe extern "C" fn record_callback(
        _samples: *const f32,
        sample_count: usize,
        first_sample: u64,
        user: *mut c_void,
    ) -> i32 {
        // SAFETY: The test keeps this record alive until callback delivery ends.
        let record = unsafe { &mut *(user as *mut CallbackRecord) };
        record.calls.push((first_sample, sample_count));
        record.result
    }

    const VALID_SETTINGS: &str = r#"{
        "temperature": 0.3,
        "sampler_decode_steps": 1,
        "eos_threshold": -4.0,
        "max_tokens": 50,
        "frames_after_eos": null,
        "quantize": false
    }"#;

    #[test]
    fn settings_require_all_fields() {
        assert!(Settings::parse(VALID_SETTINGS).is_ok());
        assert!(Settings::parse(r#"{"temperature":0.3}"#).is_err());
        assert!(
            Settings::parse(&VALID_SETTINGS.replace("        \"frames_after_eos\": null,\n", ""))
                .is_err()
        );
    }

    #[test]
    fn settings_reject_invalid_values() {
        assert!(Settings::parse(&VALID_SETTINGS.replace("0.3", "-0.1")).is_err());
        assert!(Settings::parse(&VALID_SETTINGS.replace(": 1,", ": 0,")).is_err());
        assert!(Settings::parse(&VALID_SETTINGS.replace("false", "1")).is_err());
    }

    #[test]
    fn settings_accept_int8() {
        assert!(Settings::parse(&VALID_SETTINGS.replace("false", "true")).unwrap().quantize);
    }

    #[test]
    fn settings_accept_supported_zero_values() {
        assert!(Settings::parse(&VALID_SETTINGS.replace("0.3", "0.0")).is_ok());
        assert!(Settings::parse(&VALID_SETTINGS.replace("null", "0")).is_ok());
    }

    #[test]
    fn invalid_handles_fail_without_a_model() {
        assert_eq!(pocket_stop(u64::MAX), STATUS_ERROR);
        assert_eq!(pocket_destroy(u64::MAX), STATUS_ERROR);
        assert_eq!(pocket_sample_rate(u64::MAX), 0);
    }

    #[test]
    fn null_create_arguments_fail_without_loading_a_model() {
        // SAFETY: Null pointers are an explicit error case in the C interface.
        assert_eq!(
            unsafe { pocket_create(std::ptr::null(), std::ptr::null()) },
            0
        );
    }

    #[test]
    fn callback_blocks_have_contiguous_offsets() {
        let stop = AtomicBool::new(false);
        let status = AtomicI32::new(STATUS_SUCCESS);
        let offset = AtomicU64::new(0);
        let mut record = CallbackRecord {
            calls: Vec::new(),
            result: 0,
        };
        deliver_audio(
            &[0.0; 5000],
            &stop,
            &status,
            &offset,
            record_callback,
            (&mut record as *mut CallbackRecord) as usize,
        )
        .expect("callback delivery must succeed");
        assert_eq!(record.calls, vec![(0, 4096), (4096, 904)]);
        assert_eq!(offset.load(Ordering::Relaxed), 5000);
    }

    #[test]
    fn callback_error_has_precedence_over_stop() {
        let stop = AtomicBool::new(false);
        let status = AtomicI32::new(STATUS_SUCCESS);
        let offset = AtomicU64::new(0);
        let mut record = CallbackRecord {
            calls: Vec::new(),
            result: 7,
        };
        assert!(
            deliver_audio(
                &[0.0; 5000],
                &stop,
                &status,
                &offset,
                record_callback,
                (&mut record as *mut CallbackRecord) as usize,
            )
            .is_err()
        );
        assert_eq!(record.calls, vec![(0, 4096)]);
        assert_eq!(status.load(Ordering::Acquire), 7);
        assert!(stop.load(Ordering::Acquire));
        assert_eq!(offset.load(Ordering::Relaxed), 0);
        assert!(generation_status(7, true, Ok(())).is_err());
    }

    #[test]
    fn callback_cancel_has_cancelled_status() {
        assert_eq!(
            generation_status(STATUS_CANCELLED, true, Ok(())).unwrap(),
            STATUS_CANCELLED
        );
    }
}
