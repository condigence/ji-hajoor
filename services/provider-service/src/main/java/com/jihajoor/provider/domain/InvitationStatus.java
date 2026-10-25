package com.jihajoor.provider.domain;

/** Where an agency's invitation to a worker stands. */
public enum InvitationStatus {
    /** Sent; the worker has not answered. */
    PENDING,
    /** The worker joined the agency. */
    ACCEPTED,
    /** The worker said no. */
    DECLINED,
    /** The agency withdrew it, or another agency's invitation was accepted first. */
    CANCELLED
}
