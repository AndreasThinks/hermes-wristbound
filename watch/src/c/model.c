#include "model.h"

// Key 1 held the watch's own Telegram filter until protocol v5; the phone owns it now.
#define PERSIST_OLD_INCLUDE_TELEGRAM 1
#define PERSIST_OPEN_SESSION 2
#define PERSIST_QUICK_LAUNCH 3

Model g_model;

void model_init(void) {
  memset(&g_model, 0, sizeof(g_model));
  g_model.conn = CONN_OFFLINE;
  strncpy(g_model.conn_detail, "Connecting...", DETAIL_LEN - 1);
  if (persist_exists(PERSIST_OLD_INCLUDE_TELEGRAM)) {
    persist_delete(PERSIST_OLD_INCLUDE_TELEGRAM);
  }
  if (persist_exists(PERSIST_QUICK_LAUNCH)) {
    g_model.quick_launch = persist_read_int(PERSIST_QUICK_LAUNCH) % QUICK_COUNT;
  }
  if (persist_exists(PERSIST_OPEN_SESSION)) {
    persist_read_string(PERSIST_OPEN_SESSION, g_model.open_session_id, SESSION_ID_LEN);
  }
}

void model_save_prefs(void) {
  persist_write_int(PERSIST_QUICK_LAUNCH, g_model.quick_launch);
  if (g_model.open_session_id[0]) {
    persist_write_string(PERSIST_OPEN_SESSION, g_model.open_session_id);
  } else {
    persist_delete(PERSIST_OPEN_SESSION);
  }
}

void model_truncate_messages(int count) {
  for (int i = count; i < MAX_MESSAGES; i++) {
    free(g_model.messages[i].text);
    g_model.messages[i].text = NULL;
    g_model.messages[i].id[0] = '\0';
  }
  if (g_model.message_count > count) {
    g_model.message_count = count;
  }
}

void model_clear_messages(void) {
  model_truncate_messages(0);
}

static char *prv_dup(const char *text, size_t max) {
  if (!text || !text[0]) {
    return NULL;
  }
  size_t len = strlen(text);
  if (len > max) {
    len = max;
    while (len > 0 && ((uint8_t)text[len] & 0xC0) == 0x80) {
      len--; // never split a UTF-8 character
    }
  }
  char *copy = malloc(len + 1);
  if (copy) {
    memcpy(copy, text, len);
    copy[len] = '\0';
  }
  return copy;
}

void model_set_outgoing(const char *text) {
  free(g_model.outgoing);
  g_model.outgoing = prv_dup(text, TEXT_BUDGET);
}

void model_set_stream(const char *text) {
  free(g_model.stream);
  g_model.stream = prv_dup(text, TEXT_BUDGET);
}

void model_clear_pending(void) {
  model_set_outgoing(NULL);
  model_set_stream(NULL);
}

void model_deinit(void) {
  model_save_prefs();
  model_clear_messages();
  model_clear_pending();
}

void model_set_message(int index, const char *id, uint8_t role, uint8_t flags, const char *text) {
  if (index < 0 || index >= MAX_MESSAGES) {
    return;
  }
  Message *m = &g_model.messages[index];
  strncpy(m->id, id ? id : "", MESSAGE_ID_LEN - 1);
  m->id[MESSAGE_ID_LEN - 1] = '\0';
  m->role = role;
  m->flags = flags;
  free(m->text);
  m->text = NULL;
  size_t len = text ? strlen(text) : 0;
  if (len > TEXT_BUDGET) {
    len = TEXT_BUDGET;
  }
  m->text = malloc(len + 1);
  if (m->text) {
    memcpy(m->text, text ? text : "", len);
    m->text[len] = '\0';
  }
  if (index + 1 > g_model.message_count) {
    g_model.message_count = index + 1;
  }
}

const Session *model_find_session(const char *id) {
  for (int i = 0; i < g_model.session_count; i++) {
    if (strcmp(g_model.sessions[i].id, id) == 0) {
      return &g_model.sessions[i];
    }
  }
  return NULL;
}
