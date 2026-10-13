#ifndef BYEDPI_MAIN_H
#define BYEDPI_MAIN_H

/* What byedpi_core/main.c defines and has no header for. */
int parse_args(int argc, char **argv);
int init(void);
void dump_all_cache(void);
void clear_params(char *line, char **argv);

#endif
