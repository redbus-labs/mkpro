package com.mkpro;
import com.google.genai.types.Part;
public class TestPart {
    public static void main(String[] args) {
        try {
            // Testing if fromBytes exists
            // Part.fromBytes(new byte[0], "image/jpeg");
            System.out.println("Methods: " + java.util.Arrays.toString(Part.class.getMethods()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}