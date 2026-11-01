package com.google.firebase.database;
import com.google.android.gms.tasks.Task;
public class Query {
    public Task<DataSnapshot> get() { return null; }
    public void addListenerForSingleValueEvent(ValueEventListener l) {}
    public ValueEventListener addValueEventListener(ValueEventListener l) { return l; }
    public void removeEventListener(ValueEventListener l) {}
    public Query orderByChild(String p) { return this; }
    public Query orderByKey() { return this; }
    public Query limitToLast(int n) { return this; }
    public Query limitToFirst(int n) { return this; }
    public Query equalTo(String v) { return this; }
}
