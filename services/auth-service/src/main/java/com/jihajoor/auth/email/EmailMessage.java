package com.jihajoor.auth.email;

/**
 * A plain-text email. Bodies may hold codes and invitation links, so they are never logged; apart
 * from the mail server they are sent to, only the Dev_Mail_Log adapter writes them anywhere
 * (Requirement 7.2).
 *
 * @param subject the subject line; never holds a code or link ({@link EmailMessages} keeps them in
 *                the body), so it is the one part of an email that may be logged
 * @param body    the text; never logged
 */
public record EmailMessage(String subject, String body) {
}
