/// Open only HTTP preview grants, using argument arrays rather than a shell command string.
pub fn open_preview_url(url: &str) -> Result<(), String> {
    let parsed = reqwest::Url::parse(url).map_err(|error| error.to_string())?;
    if !["http", "https"].contains(&parsed.scheme()) || parsed.host_str().is_none()
        || !parsed.username().is_empty() || parsed.password().is_some()
        || !parsed.path().starts_with("/api/v1/preview-content/") {
        return Err("Invalid preview URL".into());
    }
    let url = parsed.as_str();
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        std::process::Command::new("rundll32.exe").args(["url.dll,FileProtocolHandler", url])
            .creation_flags(0x08000000).spawn().map_err(|error| error.to_string())?;
    }
    #[cfg(target_os = "macos")]
    std::process::Command::new("open").arg(url).spawn().map_err(|error| error.to_string())?;
    #[cfg(all(unix, not(target_os = "macos")))]
    std::process::Command::new("xdg-open").arg(url).spawn().map_err(|error| error.to_string())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn rejects_non_preview_urls_before_launching_a_process() {
        for url in ["file:///tmp/result.html", "javascript:alert(1)", "https://example.com/api/v1/admin/status",
            "https://user:password@example.com/api/v1/preview-content/s/i/key/page.html"] {
            assert!(super::open_preview_url(url).is_err(), "{url}");
        }
    }
}
