#pragma once

#include <pebble.h>
#include "protocol.h"

// Hermes asking before a step it may not take on its own, such as running a shell command.
// The question takes the whole screen with the needs-you haptic, whatever was showing. Up and
// Down pick an answer (Deny is picked first, so a stray press never approves), Select sends it.
// Back puts the question away unanswered; it stays open until it is answered, and the
// conversation offers it again.
// Aplite has no room for it: the phone sends the question there as a notification with
// Approve and Deny actions instead (HELLO says whether the watchapp answers approvals).
#if !defined(PBL_PLATFORM_APLITE)
#define APPROVAL_ENABLED 1
#endif

typedef void (*ApprovalAnswerHandler)(const char *session_id, const char *request_id, ApprovalChoice choice);

void approval_show(const char *session_id, const char *request_id, const char *title, const char *text,
                   uint8_t choices, ApprovalAnswerHandler on_answer);
// Bring back the open question, if there is one.
void approval_reopen(void);
// The question was settled elsewhere (another app, a timeout, the turn ended): drop it.
void approval_close(const char *request_id);
// The session with an open question, or NULL.
const char *approval_pending_session(void);
void approval_deinit(void);
