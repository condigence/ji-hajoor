package com.jihajoor.customer.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.jihajoor.customer.config.CustomerCryptoProperties;

/** AES-256-GCM encryption of customer PII with the local data key. */
class LocalAesKmsAdapterTest {

    private static CustomerCryptoProperties withKey(String key) {
        CustomerCryptoProperties props = new CustomerCryptoProperties();
        props.setLocalDataKey(key);
        return props;
    }

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithAFreshIvAndDecryptsBack() {
        LocalAesKmsAdapter kms = new LocalAesKmsAdapter(withKey(KEY));

        String a = kms.encrypt("12 MG Road, Patna");
        String b = kms.encrypt("12 MG Road, Patna");

        assertThat(a).isNotEqualTo(b).doesNotContain("MG Road");
        assertThat(kms.decrypt(a)).isEqualTo("12 MG Road, Patna");
        assertThat(kms.decrypt(b)).isEqualTo("12 MG Road, Patna");
    }

    @Test
    void tamperedOrForeignCiphertextIsRefused() {
        LocalAesKmsAdapter kms = new LocalAesKmsAdapter(withKey(KEY));
        byte[] raw = Base64.getDecoder().decode(kms.encrypt("secret"));
        raw[raw.length - 1] ^= 1;

        assertThatThrownBy(() -> kms.decrypt(Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class);
        byte[] other = new byte[32];
        other[0] = 1;
        LocalAesKmsAdapter otherKey = new LocalAesKmsAdapter(withKey(Base64.getEncoder().encodeToString(other)));
        assertThatThrownBy(() -> otherKey.decrypt(kms.encrypt("secret"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nullsAreRejected() {
        LocalAesKmsAdapter kms = new LocalAesKmsAdapter(withKey(KEY));
        assertThatThrownBy(() -> kms.encrypt(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kms.decrypt(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thereIsNoDefaultKey_andTheKeyMustBe256Bits() {
        assertThatThrownBy(() -> new LocalAesKmsAdapter(withKey(null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalAesKmsAdapter(withKey(" "))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalAesKmsAdapter(withKey(Base64.getEncoder().encodeToString(new byte[16]))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        CustomerCryptoProperties props = withKey(KEY);
        props.setProvider("aws");
        assertThat(props.getProvider()).isEqualTo("aws");
    }
}
