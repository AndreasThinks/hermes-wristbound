#include "wristbound.h"

AppState g;

void status_line(char *out, size_t n) {
  if (!g.phone_seen) {
    snprintf(out, n, "Waiting for phone…");
  } else if (g.tool_status[0] && g.status_code == ST_OK) {
    snprintf(out, n, "%s", g.tool_status);
  } else {
    snprintf(out, n, "%s", g.status_text[0] ? g.status_text : "Connected");
  }
}

const char *submit_state_label(uint8_t s) {
  switch (s) {
    case SS_PENDING: return "Sending";
    case SS_ACCEPTED: return "Queued";
    case SS_RUNNING: return "Working";
    case SS_COMPLETED: return "Done";
    case SS_FAILED: return "Failed";
    case SS_UNCERTAIN: return "Unknown";
    default: return "Sending";
  }
}

static void init(void) {
  memset(&g, 0, sizeof(g));
  g.pref_include_telegram = 1;
  srand(time(NULL));
  comm_init();
  sessions_window_push();
  comm_hello();
}

static void deinit(void) {
  comm_deinit();
}

int main(void) {
  init();
  app_event_loop();
  deinit();
}
