package com.google.firebase.database;
import com.google.android.gms.tasks.Task;
import java.util.Map;
public class DatabaseReference extends Query {
    public DatabaseReference child(String s) { return this; }
    public DatabaseReference getParent() { return this; }
    public DatabaseReference push() { return this; }
    public String getKey() { return null; }
    public Task<Void> setValue(Object o) { return null; }
    public Task<Void> updateChildren(Map<String, Object> m) { return null; }
    public Task<Void> removeValue() { return null; }
    public OnDisconnect onDisconnect() { return null; }
}
