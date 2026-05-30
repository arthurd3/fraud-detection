//! Rust engine helpers para a arquitetura lapada (FD-passing).
//! Fase 0: `fd_ping` (link-proof). Fase 0.5: `fd_recv`/`fd_send`/`fd_socketpair`
//! (spike do recvmsg(SCM_RIGHTS) no native-image).
use std::os::raw::c_int;
use libc::c_void;
use std::sync::atomic::{AtomicI32, Ordering};

#[no_mangle]
pub extern "C" fn fd_ping(a: i32, b: i32) -> i32 {
    a + b
}

/// recvmsg(SCM_RIGHTS): recebe 1 fd pelo socket de controle. Retorna o fd ou -1.
#[no_mangle]
pub extern "C" fn fd_recv(ctrl_fd: i32) -> i32 {
    unsafe {
        let mut data: u8 = 0;
        let mut iov = libc::iovec {
            iov_base: &mut data as *mut u8 as *mut c_void,
            iov_len: 1,
        };
        let mut cmsg_buf = [0u8; 64];
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = cmsg_buf.as_mut_ptr() as *mut c_void;
        msg.msg_controllen = cmsg_buf.len() as _;
        loop {
            let n = libc::recvmsg(ctrl_fd, &mut msg, 0);
            if n < 0 {
                if *libc::__errno_location() == libc::EINTR {
                    continue;
                }
                return -1;
            }
            if n == 0 {
                return -1;
            }
            let cmsg = libc::CMSG_FIRSTHDR(&msg);
            if cmsg.is_null() {
                return -1;
            }
            if (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS {
                let p = libc::CMSG_DATA(cmsg) as *const c_int;
                return std::ptr::read_unaligned(p);
            }
            return -1;
        }
    }
}

/// sendmsg(SCM_RIGHTS): manda 1 fd. Só p/ o spike/teste (em produção quem manda é o lapada).
#[no_mangle]
pub extern "C" fn fd_send(ctrl_fd: i32, fd_to_send: i32) -> i32 {
    unsafe {
        let mut data: u8 = 0;
        let mut iov = libc::iovec {
            iov_base: &mut data as *mut u8 as *mut c_void,
            iov_len: 1,
        };
        let mut cmsg_buf = [0u8; 64];
        let cmsg = cmsg_buf.as_mut_ptr() as *mut libc::cmsghdr;
        (*cmsg).cmsg_len = libc::CMSG_LEN(4) as _;
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        std::ptr::write_unaligned(libc::CMSG_DATA(cmsg) as *mut c_int, fd_to_send);
        let mut msg: libc::msghdr = std::mem::zeroed();
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = cmsg_buf.as_mut_ptr() as *mut c_void;
        msg.msg_controllen = libc::CMSG_SPACE(4) as _;
        if libc::sendmsg(ctrl_fd, &msg, libc::MSG_NOSIGNAL) == 1 {
            0
        } else {
            -1
        }
    }
}

/// socketpair(AF_UNIX): preenche out[0],out[1]. Só p/ o spike/teste. Retorna 0/-1.
#[no_mangle]
pub extern "C" fn fd_socketpair(out: *mut i32) -> i32 {
    unsafe {
        let mut sv = [0i32; 2];
        if libc::socketpair(libc::AF_UNIX, libc::SOCK_STREAM, 0, sv.as_mut_ptr()) != 0 {
            return -1;
        }
        *out.add(0) = sv[0];
        *out.add(1) = sv[1];
        0
    }
}

// ===== Fase 2: receiver da API (lapada conecta neste Unix socket) =====
static LISTENER_FD: AtomicI32 = AtomicI32::new(-1);
static CTRL_FD: AtomicI32 = AtomicI32::new(-1);

/// Cria+bind+listen o Unix socket em $FD_SOCKET (lido do env). Chamado 1× no boot.
/// Retorna 0=ok; <0=erro (-2 sem FD_SOCKET).
#[no_mangle]
pub extern "C" fn fd_listener_init() -> i32 {
    let path = match std::env::var("FD_SOCKET") {
        Ok(p) if !p.is_empty() => p,
        _ => return -2,
    };
    let c_path = match std::ffi::CString::new(path.as_bytes()) {
        Ok(c) => c,
        Err(_) => return -3,
    };
    let bytes = c_path.as_bytes();
    unsafe {
        let fd = libc::socket(libc::AF_UNIX, libc::SOCK_STREAM, 0);
        if fd < 0 {
            return -1;
        }
        let mut addr: libc::sockaddr_un = std::mem::zeroed();
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        if bytes.len() >= addr.sun_path.len() {
            libc::close(fd);
            return -4;
        }
        libc::unlink(c_path.as_ptr());
        for (i, &b) in bytes.iter().enumerate() {
            addr.sun_path[i] = b as libc::c_char;
        }
        let len = (std::mem::size_of::<libc::sa_family_t>() + bytes.len() + 1) as libc::socklen_t;
        if libc::bind(
            fd,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            len,
        ) != 0
        {
            libc::close(fd);
            return -5;
        }
        libc::chmod(c_path.as_ptr(), 0o666);
        if libc::listen(fd, 128) != 0 {
            libc::close(fd);
            return -6;
        }
        LISTENER_FD.store(fd, Ordering::Relaxed);
        0
    }
}

/// Bloqueante: aceita a conn de controle do lapada (reconecta se cair) e
/// recvmsg 1 fd de cliente. Retorna client_fd (≥0) ou -1.
#[no_mangle]
pub extern "C" fn fd_next_client() -> i32 {
    loop {
        let mut c = CTRL_FD.load(Ordering::Relaxed);
        if c < 0 {
            let listener = LISTENER_FD.load(Ordering::Relaxed);
            if listener < 0 {
                return -1;
            }
            c = unsafe { libc::accept(listener, std::ptr::null_mut(), std::ptr::null_mut()) };
            if c < 0 {
                if unsafe { *libc::__errno_location() } == libc::EINTR {
                    continue;
                }
                return -1;
            }
            CTRL_FD.store(c, Ordering::Relaxed);
        }
        let fd = fd_recv(c);
        if fd >= 0 {
            return fd;
        }
        // conn de controle caiu → fecha e reaceita a próxima
        unsafe {
            libc::close(c);
        }
        CTRL_FD.store(-1, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn recv_roundtrip() {
        unsafe {
            // socketpair de controle + um pipe cujo write-end será passado.
            let mut pair = [0i32; 2];
            assert_eq!(fd_socketpair(pair.as_mut_ptr()), 0);
            let mut pipefd = [0i32; 2];
            assert_eq!(libc::pipe(pipefd.as_mut_ptr()), 0);
            assert_eq!(fd_send(pair[0], pipefd[1]), 0);
            let got = fd_recv(pair[1]);
            assert!(got >= 0, "fd_recv falhou");
            // escreve no fd recebido, lê na ponta original do pipe → mesmo arquivo.
            let b: u8 = 42;
            assert_eq!(libc::write(got, &b as *const u8 as *const c_void, 1), 1);
            let mut r: u8 = 0;
            assert_eq!(libc::read(pipefd[0], &mut r as *mut u8 as *mut c_void, 1), 1);
            assert_eq!(r, 42, "fd recebido não aponta pro mesmo pipe");
        }
    }
}
