use boltffi::{data, export};
use gix;

#[data]
pub struct GitResult {
    pub success: bool,
    pub message: String,
}

#[export]
pub fn init_git_repo(path: String) -> GitResult {
    match gix::init(&path) {
        Ok(_) => GitResult {
            success: true,
            message: format!("Repository successfully initialized: {}", path),
        },
        Err(e) => GitResult {
            success: false,
            message: format!("Error initializing: {}", e),
        },
    }
}