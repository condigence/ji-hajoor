package com.jihajoor.customer.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One of a customer's saved addresses, as the customer app lists them (Requirement 2.5).
 * {@code address} is the decrypted street address, or null when reverse-geocoding failed and only
 * the coordinates were stored (Requirement 2.2).
 */
public record SavedAddress(
        UUID addressId,
        String label,
        double lat,
        double lng,
        String address,
        boolean isDefault,
        Instant createdAt) {
}
