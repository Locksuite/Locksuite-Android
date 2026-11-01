package androidx.security.crypto;
public final class EncryptedSharedPreferences {
    public enum PrefKeyEncryptionScheme { AES256_SIV }
    public enum PrefValueEncryptionScheme { AES256_GCM }
    public static android.content.SharedPreferences create(android.content.Context c, String name, MasterKey k, PrefKeyEncryptionScheme ks, PrefValueEncryptionScheme vs) { return null; }
}
