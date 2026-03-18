package functional;


import java.util.stream.IntStream;

public class PrimeCheck {
    public static void main(String[] args) {
        System.out.println(isPrime(1));
        System.out.println(isPrime(2));
        System.out.println(isPrime(3));
        System.out.println(isPrime(4));
        System.out.println(isPrime(5));
    }

    public static boolean isPrime(int number) {
        if (number <= 1) {
            return false;
        }
//        for (int i = 2; i < number; i++) {
//            if (number % i == 0) {
//                return false;
//            }
//        }
//        return true;
        return IntStream.range(2, number).noneMatch(index -> number % index == 0);
    }
}
