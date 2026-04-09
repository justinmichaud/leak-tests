// Compile and run: javac simple01.java && java simple01
import java.util.Vector;
import java.util.concurrent.*;

public class simple01 {
    public volatile static Vector<int[]> leak = new Vector<>();
    public static void main(String[] args) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            leak.add(new int[1000]);
            System.out.println("Hello!");
        }, 0, 1, TimeUnit.SECONDS);
    }
}
