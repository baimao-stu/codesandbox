import java.io.*;
/**
 * @author baimao
 * @title Main
 */
public class Main {

    public static void main(String[] args) {
        OutputStreamWriter out = null;
        try {
            out = new OutputStreamWriter(new FileOutputStream("666"));
            out.write("Asdsad");
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
