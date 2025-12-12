package com.xceptance.neodymium.ai.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.imageio.ImageIO;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;

import com.xceptance.neodymium.util.AllureAddons;
import com.xceptance.neodymium.util.Neodymium;

public class ScreenshotMarker
{

    // Configuration for the marker
    private static final int MARKER_SIZE = 5;

    // You can use Color.PINK, or define a custom brighter pink if needed like below:
    // private static final Color MARKER_COLOR = new Color(255, 20, 147); // DeepPink
    private static final Color MARKER_COLOR = Color.PINK;

    /**
     * Takes a screenshot of the current viewport and draws a 5x5 pink square centered at the given coordinates.
     *
     * @param x
     *            The X coordinate relative to the viewport bounds.
     * @param y
     *            The Y coordinate relative to the viewport bounds.
     * @param baseFileName
     *            The desired start of the filename (e.g., "click-failure").
     * @return The final saved File object.
     */
    public static byte[] takeScreenshotWithMarker(int x, int y, String baseFileName)
    {
        try
        {
            // 1. Capture the raw screenshot using underlying Selenium driver
            // We use this method to ensure we get the current viewport dimensions accurately
            File rawScreenshot = ((TakesScreenshot) Neodymium.getDriver())
                                                                          .getScreenshotAs(OutputType.FILE);

            // 2. Read image into memory for editing
            BufferedImage image = ImageIO.read(rawScreenshot);

            if (x > 0 && y > 0)
            {
                // 3. Create a Graphics2D context to draw on the image
                Graphics2D g2d = image.createGraphics();

                // ---- Drawing Logic ----
                g2d.setColor(MARKER_COLOR);

                // To center a 5x5 square on (x,y), we must offset the top-left corner by 2 pixels
                // e.g., if target is (100,100), we draw from (98, 98) to (103, 103).
                int offset = MARKER_SIZE / 2; // Results in 2 for integer division of 5
                int drawX = x - offset;
                int drawY = y - offset;

                // Draw a filled square
                g2d.fillRect(drawX, drawY, MARKER_SIZE, MARKER_SIZE);
                // Optional: Draw a border around the square for better visibility on pink backgrounds
                // g2d.setColor(Color.BLACK);
                // g2d.drawRect(drawX, drawY, MARKER_SIZE, MARKER_SIZE);
                // -----------------------

                // Dispose context to free resources
                g2d.dispose();
            }
            // 4. Prepare output directory and filename
            String timestamp = System.currentTimeMillis() + "";
            String finalFileName = timestamp + "_" + baseFileName + "_marked.png";
            // Default Selenide reports folder, change if necessary
            String reportsPath = "build/reports/tests";
            Path outputDir = Paths.get(reportsPath);

            if (!Files.exists(outputDir))
            {
                Files.createDirectories(outputDir);
            }

            File outputFile = outputDir.resolve(finalFileName).toFile();

            // 5. Write the modified image to disk
            ImageIO.write(image, "png", outputFile);

            // 6. Convert BufferedImage to byte[]
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Choose your format here ("png", "jpg", "gif")
            ImageIO.write(image, "png", baos);
            
            AllureAddons.addAttachmentToStep("Screenshot" + baseFileName, "image/png", ".png", new FileInputStream(outputFile));

            byte[] imageBytes = baos.toByteArray();

            return imageBytes;

        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to mark coordinate on screenshot", e);
        }
    }
}