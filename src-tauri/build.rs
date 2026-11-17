fn main() {
    println!("cargo:rerun-if-env-changed=HARNEX_UPDATE_ENDPOINT");
    println!("cargo:rerun-if-env-changed=HARNEX_UPDATE_PUBKEY");
    tauri_build::build()
}
