package org.dean.experimental.functional;

public class AnonymousClass {
    public static void main(String[] args) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("In another thread");
            }
        });
//        Thread t = new Thread(() -> System.out.println("In another thread"));
        t.start();
        System.out.println("In main thread");
    }
}
