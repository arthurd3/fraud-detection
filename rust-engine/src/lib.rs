//! Fase 0: link-proof. Prova que um staticlib Rust extern "C" linka no
//! binário GraalVM native-image via @CFunction. `fd_ping` vira `fd_init`/
//! `fd_search` (o motor de busca) na Fase 1.
#[no_mangle]
pub extern "C" fn fd_ping(a: i32, b: i32) -> i32 {
    a + b
}
