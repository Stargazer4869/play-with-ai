package org.dean.experimental;

public class TestInterrupt {
    public static void main(String[] args) throws Exception {
        Runnable runnable = () -> {
            try {
                Thread.sleep(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("Current thread is interrupted: " + Thread.currentThread().isInterrupted());
        };
        Thread t = new Thread(runnable);
        t.start();
        Thread.sleep(1000L);
        t.interrupt();
        Thread.sleep(1000L);
    }
}
