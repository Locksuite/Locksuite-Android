package com.google.firebase.database;
public class DataSnapshot {
    public boolean exists() { return false; }
    public Object getValue() { return null; }
    public <T> T getValue(Class<T> c) { return null; }
    public DataSnapshot child(String s) { return this; }
    public Iterable<DataSnapshot> getChildren() { return null; }
    public String getKey() { return null; }
    public boolean hasChild(String s) { return false; }
    public boolean hasChildren() { return false; }
    public long getChildrenCount() { return 0; }
    public DatabaseReference getRef() { return null; }
}
