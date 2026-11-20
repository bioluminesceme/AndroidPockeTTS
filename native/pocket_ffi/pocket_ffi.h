#ifndef POCKET_FFI_H
#define POCKET_FFI_H

#include <stddef.h>
#include <stdint.h>

#define POCKET_MAX_CALLBACK_SAMPLES 4096u
#define POCKET_STATUS_SUCCESS 0
#define POCKET_STATUS_CANCELLED 1
#define POCKET_STATUS_ERROR (-1)

#if defined(_WIN32)
#if defined(POCKET_FFI_BUILD)
#define POCKET_FFI_API __declspec(dllexport)
#elif defined(POCKET_FFI_STATIC)
#define POCKET_FFI_API
#else
#define POCKET_FFI_API __declspec(dllimport)
#endif
#define POCKET_FFI_CALL __cdecl
#else
#define POCKET_FFI_API
#define POCKET_FFI_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* The sample pointer is valid only during the callback. Return 0 to continue.
 * Return 1 to cancel. Any other value causes an error. The callback runs on
 * the decoder thread. Only pocket_stop can use the same handle from it. */
typedef int32_t(POCKET_FFI_CALL *pocket_audio_callback)(
    const float *samples,
    size_t sample_count,
    uint64_t first_sample,
    void *user);

/* String pointers must contain valid NUL-terminated UTF-8 for the call. */
POCKET_FFI_API uint64_t POCKET_FFI_CALL pocket_create(
    const char *config_path,
    const char *settings_json);
POCKET_FFI_API int32_t POCKET_FFI_CALL pocket_select_voice(
    uint64_t handle,
    const char *speaker_path);

/* This call is synchronous. Offsets start at zero and have no gaps.
 * No callback occurs after this function returns. */
POCKET_FFI_API int32_t POCKET_FFI_CALL pocket_generate(
    uint64_t handle,
    const char *text,
    pocket_audio_callback callback,
    void *user);
POCKET_FFI_API int32_t POCKET_FFI_CALL pocket_stop(uint64_t handle);

/* Destroy returns POCKET_STATUS_ERROR when the handle is busy. */
POCKET_FFI_API int32_t POCKET_FFI_CALL pocket_destroy(uint64_t handle);
POCKET_FFI_API uint32_t POCKET_FFI_CALL pocket_sample_rate(uint64_t handle);

/* The returned pointer is valid until the next interface call on this thread. */
POCKET_FFI_API const char *POCKET_FFI_CALL pocket_last_error(void);

#ifdef __cplusplus
}
#endif

#endif
