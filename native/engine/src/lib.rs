pub mod audio;
pub mod config;
pub mod defaults;
pub mod ffi;
pub mod model;
pub mod rng;
pub mod text;
pub mod tokenizer;
mod tensor_validation;
pub mod weights;

pub mod download {
    use anyhow::{Result, bail};
    use std::path::PathBuf;

    fn is_extended_local_drive(path: &str) -> bool {
        let Some(rest) = path.strip_prefix("\\\\?\\") else {
            return false;
        };
        let bytes = rest.as_bytes();
        bytes.len() >= 3
            && bytes[0].is_ascii_alphabetic()
            && bytes[1] == b':'
            && matches!(bytes[2], b'\\' | b'/')
    }

    pub(crate) fn is_remote_location(path: &str) -> bool {
        path.contains("://")
            || path.starts_with("//")
            || (path.starts_with("\\\\") && !is_extended_local_drive(path))
    }

    /// Return an existing local file. Network locations are not accepted by this library.
    pub fn download_if_necessary(file_path: &str) -> Result<PathBuf> {
        if is_remote_location(file_path) {
            bail!("network locations are not supported: {file_path}")
        }
        let path = PathBuf::from(file_path);
        if !path.is_file() {
            bail!("local file does not exist: {}", path.display())
        }
        Ok(path)
    }

    #[cfg(test)]
    mod tests {
        use super::is_remote_location;

        #[test]
        fn remote_locations_exclude_extended_local_drives() {
            assert!(!is_remote_location(r"C:\models\model.safetensors"));
            assert!(!is_remote_location(r"\\?\C:\models\model.safetensors"));
            assert!(is_remote_location(r"\\server\share\model.safetensors"));
            assert!(is_remote_location(
                r"\\?\UNC\server\share\model.safetensors"
            ));
            assert!(is_remote_location("https://example.test/model.safetensors"));
        }
    }
}
