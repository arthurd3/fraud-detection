#ifndef FDSEARCH_H
#define FDSEARCH_H
#include <stdint.h>
int32_t fd_ping(int32_t a, int32_t b);
int32_t fd_recv(int32_t ctrl_fd);
int32_t fd_send(int32_t ctrl_fd, int32_t fd_to_send);
int32_t fd_socketpair(int32_t* out);
int32_t fd_listener_init(void);
int32_t fd_next_client(void);
int32_t fd_raise_memlock_rlimit(void);
int32_t fd_mlock_region(int64_t addr, int64_t len);
/* Motor de busca KD-tree (port de KdTree.search). */
int32_t fd_init(void);
int32_t fd_search(int64_t q_addr);
#endif
