package com.google.android.gms.tasks;
public abstract class Task<T> {
    public abstract boolean isComplete();
    public abstract boolean isSuccessful();
    public abstract T getResult();
    public abstract Exception getException();
    public abstract Task<T> addOnSuccessListener(OnSuccessListener<? super T> l);
    public abstract Task<T> addOnFailureListener(OnFailureListener l);
    public abstract Task<T> addOnCompleteListener(OnCompleteListener<T> l);
}
