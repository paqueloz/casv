import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * Version that generates something quite good with
 * java PosterGenerator 90 300 200 600 300 1500 a.png b.png c.png
 * or
 * java PosterGenerator 90 300 300 500 300 3000 b.png c.png a.png
 * and then export as 300 dpi PDF with Inkscape (200 dpi could
 * be good too but I didn't try...)
 * </p>
 * <p>
 * pngs don't need to be a huge resolution, svg can be embedded
 * but the result was very pixelated and it seemed a bit complicated
 * to incorporate cleanly
 * </p>
 * <p>
 * Compile and run with Java 21
 * </p>
 */
public class PosterGenerator {

    static int cmToPx(double cm, double dpi) {
        return (int) Math.round(cm / 2.54 * dpi);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println(
                    "Usage: java PosterGenerator <widthCm> <heightCm> <dpi> " +
                            "<gapH> <gapV> <thumbW> <img1> [img2 ...]");
            System.exit(1);
        }

        double widthCm = Double.parseDouble(args[0]);
        double heightCm = Double.parseDouble(args[1]);
        double dpi = Double.parseDouble(args[2]);
        int gapH = Integer.parseInt(args[3]); // horizontal gap in pixels
        int gapV = Integer.parseInt(args[4]); // vertical gap in pixels
        int thumbW = Integer.parseInt(args[5]); // width and height in pixels
                                                // only indicative as narrow
                                                // images will be expanded to
                                                // occupy similar area
        List<String> imagePaths = new ArrayList<>();
        for (int i = 6; i < args.length; i++)
            imagePaths.add(args[i]);

        if (imagePaths.isEmpty()) {
            System.err.println("Erreur : fournissez au moins une image.");
            System.exit(1);
        }

        int W = cmToPx(widthCm, dpi); // width in pixels
        int H = cmToPx(heightCm, dpi); // height in pixels

        List<Logo> logos = loadImages(imagePaths);

        String outputFile = "poster.svg";
        generateFile(W, H, widthCm, heightCm, gapH, gapV, thumbW, logos, outputFile);
        System.out.printf("SVG généré : %s  (%d × %d px @ %.0f dpi)%n", outputFile, W, H, dpi);
    }

    private static List<Logo> loadImages(List<String> imagePaths) {
        List<Logo> result = new ArrayList<>();
        for (int index = 0; index < imagePaths.size(); index++) {
            Logo i = loadImage(imagePaths.get(index), index);
            result.add(i);
            System.out.println(
                    "%s width=%d height=%d ratio=%.3f expand=%.3f".formatted(
                            i.imagePath, i.width, i.height, i.aspectRatio, i.expand));
        }
        return result;
    }

    private static Logo loadImage(String imagePath, int index) {
        try {
            String mimeType = detectMime(imagePath);
            byte[] bytes = Files.readAllBytes(Path.of(imagePath));
            String b64 = Base64.getEncoder().encodeToString(bytes);
            int[] dimensions = switch (mimeType) {
                case "image/png" -> getPngDimensions(b64);
                case "image/svg+xml" -> getSvgDimensions(b64);
                default -> new int[] { 0, 0 };
            };
            // ratio = 1 occupe le mieux l'espace carré
            // ratio < 1 = image verticale (pas en 2026)
            // ratio > 1 = image horizontale (Helios, Versoix)
            // agrandir dans les deux directions d'un facteur racine(ratio)
            int width = dimensions[0];
            int height = dimensions[1];
            double aspectRatio = (double) width / height;
            double expand = Math.sqrt(aspectRatio);
            return new Logo(imagePath, mimeType, b64, width, height, aspectRatio, expand, index);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void generateFile(int W, int H,
            double widthCm, double heightCm,
            int gapH, int gapV, int thumbW,
            List<Logo> logos, String outputFile) throws Exception {

        try (PrintWriter pw = new PrintWriter(outputFile, "UTF-8")) {
            writeSVG(W, H, widthCm, heightCm, gapH, gapV, thumbW, logos, pw);
        }
    }

    static void writeSVG(int W, int H,
            double widthCm, double heightCm,
            int gapH, int gapV, int thumbW,
            List<Logo> logos, Writer writer) throws Exception {

        int n = logos.size();

        // En-tête avec dimensions physiques (cm) et viewBox en pixels
        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        writer.write(String.format(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"\n" +
                        "     xmlns:xlink=\"http://www.w3.org/1999/xlink\"\n" +
                        "     width=\"%.4fcm\" height=\"%.4fcm\"\n" +
                        "     viewBox=\"0 0 %d %d\">\n",
                widthCm, heightCm, W, H));

        // <defs> : un <symbol> par image source (carré unitaire, redimensionné à
        // l'usage)
        // meet : était slice et les images étaient clippées
        writer.write("  <defs>\n");
        for (int i = 0; i < n; i++) {
            Logo current = logos.get(i);
            writer.write(String.format(
                    "    <!-- Image source %d : %s -->\n" +
                            "    <symbol id=\"img%d\" viewBox=\"0 0 100 100\" preserveAspectRatio=\"xMidYMid meet\">\n"
                            +
                            "      <image href=\"%s\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\"\n" +
                            "             preserveAspectRatio=\"xMidYMid meet\"/>\n" +
                            "    </symbol>\n",
                    i, current.imagePath, i, current.toDataURI(), 100, 100));
        }
        writer.write("  </defs>\n\n");

        // Fond blanc
        writer.write(String.format("  <rect width=\"%d\" height=\"%d\" fill=\"white\"/>\n\n", W, H));

        writer.write("  <g>\n");

        // Images
        placeImages(W, H, gapH, gapV, thumbW, logos, writer);

        // fin du SVG
        writer.write("  </g>\n");
        writer.write("</svg>\n");
    }

    static void placeImages(int W, int H,
            int gapH, int gapV, int thumbW,
            List<Logo> logos, Writer writer) throws Exception {

        int imgIdx = 0;
        Logo logo = logos.get(imgIdx);

        int margin = 200; // pour éviter les images collées au bord

        // centre de l'image
        double cx = margin + (thumbW * logo.expand / 2);
        double cy = margin + (thumbW / 2);

        while (true) {

            writeImage(cx, cy, thumbW, logo, writer);

            // move right half of current image
            cx += thumbW * logo.expand / 2;

            // move right for gapH
            cx += gapH;

            imgIdx++;
            if (imgIdx >= logos.size()) {
                imgIdx = 0;
            }
            logo = logos.get(imgIdx);

            // move right half of next image
            cx += thumbW * logo.expand / 2;

            // If we exceed the width, move to the next line
            if (cx + (thumbW * logo.expand / 2) + margin > W) {
                cx -= W;
                cx += thumbW * logo.expand / 2;

                // truc pour les images qui dépassent à gauche
                double restart = margin + (thumbW * logo.expand / 2);
                if (cx < restart) {
                    cx = restart;
                }

                cy += thumbW + gapV;
                if (cy + (thumbW / 2) + gapV > H) {
                    break;
                }
            }
        }
    }

    /**
     * Place une image, cx, cy sont au centre
     */
    static void writeImage(double cx, double cy, int thumbW, Logo logo, Writer writer) throws Exception {
        double expand = logo.expand;
        double offset = thumbW * expand / 2;
        double ix = cx - offset;
        double iy = cy - offset;

        writer.write(String.format(
                "    <use href=\"#img%d\" x=\"%.3f\" y=\"%.3f\" width=\"%.3f\" height=\"%.3f\"/>\n",
                logo.index, ix, iy, thumbW * expand, thumbW * expand));
        // writer.write(String.format(
        // " <rect x=\"%.3f\" y=\"%.3f\" width=\"%.3f\" height=\"%.3f\" rx=\"4\"
        // fill=\"none\" stroke=\"blue\" stroke-width=\"2\"/>\n",
        // ix, iy, thumbW*expand, thumbW*expand));
    }

    // -----------------------------------------------------------------------
    // Utilitaires
    // -----------------------------------------------------------------------

    static String detectMime(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".png"))
            return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
            return "image/jpeg";
        if (lower.endsWith(".gif"))
            return "image/gif";
        if (lower.endsWith(".webp"))
            return "image/webp";
        if (lower.endsWith(".svg"))
            return "image/svg+xml";
        return "image/png";
    }

    public static int[] getPngDimensions(InputStream is) throws IOException {
        byte[] header = new byte[24];
        if (is.read(header) < 24)
            throw new IOException("Not enough data");

        // Check PNG signature: 8 bytes
        if (header[0] != (byte) 0x89 || header[1] != 'P' ||
                header[2] != 'N' || header[3] != 'G') {
            throw new IOException("Not a PNG file");
        }

        // Width at bytes 16-19, Height at bytes 20-23 (big-endian)
        int width = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                | ((header[18] & 0xFF) << 8) | (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                | ((header[22] & 0xFF) << 8) | (header[23] & 0xFF);

        return new int[] { width, height };
    }

    public static int[] getPngDimensions(String base64) throws IOException {
        String b64 = base64.replaceFirst("^data:image/png;base64,", "");
        byte[] bytes = Base64.getDecoder().decode(b64);
        return getPngDimensions(new ByteArrayInputStream(bytes));
    }

    public static int[] getSvgDimensions(String b64) throws IOException {

        // Decode base64 to string
        String b64clean = b64.replaceFirst("^data:image/svg\\+xml;base64,", "");
        String svg = new String(Base64.getDecoder().decode(b64clean));

        // Try width/height attributes first: <svg ... width="800" height="600" ...>
        Pattern attrPattern = Pattern.compile(
                "<svg[^>]*\\swidth=[\"']([\\d.]+)[\"'][^>]*\\sheight=[\"']([\\d.]+)[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = attrPattern.matcher(svg);
        if (m.find()) {
            return new int[] { (int) Double.parseDouble(m.group(1)),
                    (int) Double.parseDouble(m.group(2)) };
        }

        // Fallback: parse viewBox="minX minY width height"
        Pattern vbPattern = Pattern.compile(
                "<svg[^>]*\\sviewBox=[\"'][\\d.]+\\s+[\\d.]+\\s+([\\d.]+)\\s+([\\d.]+)[\"']",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        m = vbPattern.matcher(svg);
        if (m.find()) {
            return new int[] { (int) Double.parseDouble(m.group(1)),
                    (int) Double.parseDouble(m.group(2)) };
        }

        throw new IOException("Could not determine SVG dimensions");
    }

    record Logo(
            String imagePath,
            String mimeType,
            String b64,
            int width,
            int height,
            double aspectRatio,
            double expand,
            int index) {
        String toDataURI() {
            return "data:" + mimeType + ";base64," + b64;
        }
    }
}
