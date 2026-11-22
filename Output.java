public class Input {
    public static void main(String[] args) {
        int age = 25;
        int salary = 8000;
        if ((((java.util.function.Supplier<Boolean>) () -> { var $gce$0 = (age); return (($gce$0 >= (18)) && ($gce$0 <= (65))); }).get()) && (salary > 5000)) {
            System.out.println("Accepted");
        }
    }
}
