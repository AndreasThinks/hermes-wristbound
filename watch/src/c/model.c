#include "model.h"

#define PERSIST_INCLUDE_TELEGRAM 1
#define PERSIST_OPEN_SESSION 2

Model g_model;

void model_init(void) {
  memset(&g_model, 0, sizeof(g_model));
  g_model.conn = CONN_OFFLINE;
  strncpy(g_model.conn_detail, "Connecting...", DETAIL_LEN - 1);
  g_model.include_telegram = persist_exists(PERSIST_INCLUDE_TELEGRAM)
      ? persist_read_bool(PERSIST_INCLUDE_TELEGRAM) : true;
  if (persist_exists(PERSIST_OPEN_SESSION)) {
    persist_read_string(PERSIST_OPEN_SESSION, g_model.open_session_id, SESSION_ID_LEN);
  }
}

void model_save_prefs(void) {
  persist_write_bool(PERSIST_INCLUDE_TELEGRAM, g_model.include_telegram);
  if (g_model.open_session_id[0]) {
    persist_write_string(PERSIST_OPEN_SESSION, g_model.open_session_id);
  } else {
    persist_delete(PERSIST_OPEN_SESSION);
  }
}

void model_clear_messages(void) {
  for (int i = 0; i < MAX_MESSAGES; i++) {
    free(g_model.messages[i].text);
    g_model.messages[i].text = NULL;
    g_model.messages[i].id[0] = '\0';
  }
  g_model.message_count = 0;
}

void model_deinit(void) {
  model_save_prefs();
  model_clear_messages();
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
