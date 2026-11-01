package com.google.firebase.database;
public interface ValueEventListener {
    void onDataChange(DataSnapshot snapshot);
    void onCancelled(DatabaseError error);
}
