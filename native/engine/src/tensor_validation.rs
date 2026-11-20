use anyhow::{Context, Result, bail};
use std::io::Read;
use std::path::Path;

pub fn validate_file(path: &Path) -> Result<()> {
    let file = std::fs::File::open(path)?;
    let size = file.metadata()?.len();
    validate_header(file, size).with_context(|| format!("invalid tensor file: {}", path.display()))
}

fn validate_header(mut input: impl Read, file_size: u64) -> Result<()> {
    let mut prefix = [0u8; 8];
    input.read_exact(&mut prefix)?;
    let length = u64::from_le_bytes(prefix);
    let available = file_size.checked_sub(8).context("tensor file is too short")?;
    // This is the header limit in the pinned safetensors library.
    if length > 100_000_000 || length > available {
        bail!("tensor header exceeds the file bounds or header limit")
    }
    let mut header = vec![0u8; usize::try_from(length)?];
    input.read_exact(&mut header)?;
    // Metadata deserialization checks shapes, dtypes, offsets, and integer overflow.
    let metadata: safetensors::tensor::Metadata = serde_json::from_slice(&header)?;
    if u64::try_from(metadata.data_len())? != available - length {
        bail!("tensor payload length does not match its metadata")
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn check(header: &str, payload_length: u64) -> Result<()> {
        let mut data = (header.len() as u64).to_le_bytes().to_vec();
        data.extend_from_slice(header.as_bytes());
        let size = data.len() as u64 + payload_length;
        validate_header(data.as_slice(), size)
    }

    #[test]
    fn corrupt_length_is_rejected_before_allocation() {
        assert!(validate_header(&u64::MAX.to_le_bytes()[..], 21).is_err());
        assert!(validate_header(&[0u8; 3][..], 3).is_err());
    }

    #[test]
    fn valid_tensor_and_payload_length_are_checked() {
        let header = r#"{"x":{"dtype":"F32","shape":[1],"data_offsets":[0,4]}}"#;
        assert!(check(header, 4).is_ok());
        assert!(check(header, 3).is_err());
        assert!(check(header, 5).is_err());
    }

    #[test]
    fn bad_offsets_and_shape_overflow_are_rejected() {
        assert!(check(r#"{"x":{"dtype":"F32","shape":[1],"data_offsets":[4,0]}}"#, 4).is_err());
        assert!(check(r#"{"x":{"dtype":"F32","shape":[18446744073709551615,2],"data_offsets":[0,4]}}"#, 4).is_err());
    }
}
