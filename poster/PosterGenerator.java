import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PosterGenerator {

    static int cmToPx(double cm, double dpi) {
        return (int) Math.round(cm / 2.54 * dpi);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 7) {
            System.err.println(
                "Usage: java PosterGenerator <widthCm> <heightCm> <dpi> " +
                "<gap> <angleRad> <thumbW> <img1> [img2 ...]");
            System.exit(1);
        }

        double widthCm     = Double.parseDouble(args[0]);
        double heightCm    = Double.parseDouble(args[1]);
        double dpi         = Double.parseDouble(args[2]);
        int    gap = Integer.parseInt(args[3]);
        double angle       = Double.parseDouble(args[4]);
        int    thumbW      = Integer.parseInt(args[5]);

        List<String> imagePaths = new ArrayList<>();
        for (int i = 6; i < args.length; i++) imagePaths.add(args[i]);

        if (imagePaths.isEmpty()) {
            System.err.println("Erreur : fournissez au moins une image.");
            System.exit(1);
        }

        int W = cmToPx(widthCm, dpi);
        int H = cmToPx(heightCm, dpi);

        List<Logo> logos = loadImages(imagePaths);

        String svg = generateSVG(W, H, widthCm, heightCm, gap, angle, thumbW, logos);
        String outputFile = "poster.svg";
        Files.writeString(Path.of(outputFile), svg);
        System.out.printf("SVG généré : %s  (%d × %d px @ %.0f dpi)%n", outputFile, W, H, dpi);
    }

    private static List<Logo> loadImages(List<String> imagePaths) {
        return imagePaths.stream()
            .map(PosterGenerator::loadImage)
            .peek(i -> System.out.println(
                "%s width=%d height=%d ratio=%.3f expand=%.3f".formatted(
                    i.imagePath, i.width, i.height, i.aspectRatio, i.expand
                )
            ))
            .toList();
    }

    private static Logo loadImage(String imagePath) {
        try {
            String mimeType = detectMime(imagePath);
            byte[] bytes = Files.readAllBytes(Path.of(imagePath));
            String b64   = Base64.getEncoder().encodeToString(bytes);
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
            return new Logo(imagePath, mimeType, b64, width, height, aspectRatio, expand);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Génération SVG
    // -----------------------------------------------------------------------

    static String generateSVG(int W, int H,
                               double widthCm, double heightCm,
                               int gap, double angle, int thumbW,
                               List<Logo> logos) throws Exception {
        int n = logos.size();

        double cosA = Math.cos(angle);
        double sinA = Math.sin(angle);

        // ------------------------------------------------------------------
        // 1. Taille des vignettes
        //
        // La longueur d'une ligne traversant le poster (de bord à bord dans
        // la direction de l'angle) vaut :
        //   lineLength = |W·cos| + |H·sin|
        //
        // On en déduit thumbW tel que (repetitions * n) vignettes tiennent
        // sur cette longueur. 
        // Ce n'est plus le cas avec thumbW fixe.
        // ------------------------------------------------------------------
        double lineLength = Math.abs(W * cosA) + Math.abs(H * sinA);
        int thumbH     = thumbW; // vignettes carrées

        // Demi-diagonale de la vignette = marge de sécurité pour rester dans le poster.
        // Les images sont verticales donc leurs coins sont à (±thumbW/2, ±thumbH/2)
        // du centre. La distance max au centre est la demi-diagonale.
        double halfDiag = Math.sqrt(thumbW * thumbW + thumbH * thumbH) / 2.0;

        // ------------------------------------------------------------------
        // 2. Zone sûre pour les centres des images
        //
        // Dans la direction de la ligne : safeLine = lineLength - 2·halfDiag
        // Dans la direction normale     : safeNormal = normalExtent - 2·halfDiag
        //   où normalExtent = |W·sin| + |H·cos|
        // ------------------------------------------------------------------
        double normalExtent = Math.abs(W * sinA) + Math.abs(H * cosA);
        double safeLine     = lineLength   - 2 * halfDiag;
        double safeNormal   = normalExtent - 2 * halfDiag;

        // ------------------------------------------------------------------
        // 3. Nombre de lignes et espacement
        //
        // On veut remplir safeNormal avec des lignes espacées de thumbH.
        // numLines = round(safeNormal / thumbH) + 1  (pour inclure les deux bords)
        // ------------------------------------------------------------------
        int    numLines    = Math.max(1, (int) Math.round(safeNormal / (thumbH+gap)) + 1);
        double lineSpacing = (numLines > 1) ? safeNormal / (numLines - 1) : 0;

        // ------------------------------------------------------------------
        // 4. Nombre de vignettes par ligne et espacement sur la ligne
        // ------------------------------------------------------------------
        int    thumbsPerLine    = Math.max(1, (int) Math.round(safeLine / (thumbW+gap)) + 1);
        double lineItemSpacing  = (thumbsPerLine > 1) ? safeLine / (thumbsPerLine - 1) : 0;

        // ------------------------------------------------------------------
        // 5. Encodage des images – une seule fois chacune
        // ------------------------------------------------------------------
        // déjà dans les logo

        // ------------------------------------------------------------------
        // 6. Construction du SVG
        // ------------------------------------------------------------------
        StringBuilder sb = new StringBuilder();

        // En-tête avec dimensions physiques (cm) et viewBox en pixels
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append(String.format(
            "<svg xmlns=\"http://www.w3.org/2000/svg\"\n" +
            "     xmlns:xlink=\"http://www.w3.org/1999/xlink\"\n" +
            "     width=\"%.4fcm\" height=\"%.4fcm\"\n" +
            "     viewBox=\"0 0 %d %d\">\n",
            widthCm, heightCm, W, H));

        // <defs> : un <symbol> par image source (carré unitaire, redimensionné à l'usage)
        // meet : était slice et les images étaient clippées
        sb.append("  <defs>\n");
        for (int i = 0; i < n; i++) {
            Logo current = logos.get(i);
            sb.append(String.format(
                "    <!-- Image source %d : %s -->\n" +
                "    <symbol id=\"img%d\" viewBox=\"0 0 100 100\" preserveAspectRatio=\"xMidYMid meet\">\n" +
                "      <image href=\"%s\" x=\"0\" y=\"0\" width=\"%d\" height=\"%d\"\n" +
                "             preserveAspectRatio=\"xMidYMid meet\"/>\n" +
                "    </symbol>\n",
                i, current.imagePath, i, current.toDataURI(), 100, 100));
        }
        sb.append("  </defs>\n\n");

        // Fond blanc
        sb.append(String.format("  <rect width=\"%d\" height=\"%d\" fill=\"white\"/>\n\n", W, H));

        // ------------------------------------------------------------------
        // 7. Placement des vignettes
        //
        // Centre du poster : (cx, cy)
        // Direction de la ligne  : (cosA, sinA)
        // Direction normale      : (-sinA, cosA)
        //
        // Les centres des lignes sont distribués symétriquement autour du
        // centre du poster dans la direction normale, de -safeNormal/2 à +safeNormal/2.
        // Sur chaque ligne, les centres des images sont distribués de
        // -safeLine/2 à +safeLine/2 dans la direction de la ligne.
        // ------------------------------------------------------------------
        double cx = W / 2.0;
        double cy = H / 2.0;
        double dx =  cosA, dy =  sinA;  // direction de la ligne
        double nx = -sinA, ny =  cosA;  // direction normale

        double normalStart = -safeNormal / 2.0;
        double lineStart   = -safeLine   / 2.0;

        sb.append("  <g>\n");

        for (int line = 0; line < numLines; line++) {
            double normalOff = (numLines > 1) ? normalStart + line * lineSpacing : 0;
            double lineCX    = cx + normalOff * nx;
            double lineCY    = cy + normalOff * ny;

            for (int t = 0; t < thumbsPerLine; t++) {
                int imgIdx = t % n;  // séquence cyclique

                double alongOff = (thumbsPerLine > 1) ? lineStart + t * lineItemSpacing : 0;
                double imgCX    = lineCX + alongOff * dx;
                double imgCY    = lineCY + alongOff * dy;

                // Coin supérieur gauche – images verticales, aucune rotation
                double ix = imgCX - thumbW / 2.0;
                double iy = imgCY - thumbH / 2.0;

                double expand = logos.get(imgIdx).expand;

                sb.append(String.format(
                    "    <use href=\"#img%d\" x=\"%.3f\" y=\"%.3f\" width=\"%.3f\" height=\"%.3f\"/>\n",
                    imgIdx, ix, iy, thumbW*expand, thumbH*expand));
                sb.append(String.format(
                    "    <rect x=\"%.3f\" y=\"%.3f\" width=\"%.3f\" height=\"%.3f\" rx=\"4\" fill=\"none\" stroke=\"blue\" stroke-width=\"2\"/>\n",
                    ix, iy, thumbW*expand, thumbH*expand));
    
            }
        }

        sb.append("  </g>\n");
        sb.append("</svg>\n");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Utilitaires
    // -----------------------------------------------------------------------

    static String detectMime(String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".png"))                            return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))                            return "image/gif";
        if (lower.endsWith(".webp"))                           return "image/webp";
        if (lower.endsWith(".svg"))                            return "image/svg+xml";
        return "image/png";
    }

    public static int[] getPngDimensions(InputStream is) throws IOException {
        byte[] header = new byte[24];
        if (is.read(header) < 24) throw new IOException("Not enough data");

        // Check PNG signature: 8 bytes
        if (header[0] != (byte)0x89 || header[1] != 'P' ||
            header[2] != 'N'        || header[3] != 'G') {
            throw new IOException("Not a PNG file");
        }

        // Width at bytes 16-19, Height at bytes 20-23 (big-endian)
        int width  = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                   | ((header[18] & 0xFF) <<  8) |  (header[19] & 0xFF);
        int height = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                   | ((header[22] & 0xFF) <<  8) |  (header[23] & 0xFF);

        return new int[]{width, height};
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
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        Matcher m = attrPattern.matcher(svg);
        if (m.find()) {
            return new int[]{ (int) Double.parseDouble(m.group(1)),
                            (int) Double.parseDouble(m.group(2)) };
        }

        // Fallback: parse viewBox="minX minY width height"
        Pattern vbPattern = Pattern.compile(
            "<svg[^>]*\\sviewBox=[\"'][\\d.]+\\s+[\\d.]+\\s+([\\d.]+)\\s+([\\d.]+)[\"']",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );
        m = vbPattern.matcher(svg);
        if (m.find()) {
            return new int[]{ (int) Double.parseDouble(m.group(1)),
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
        double expand
    ) {
        String toDataURI() {
            return "data:" + mimeType + ";base64," + b64;
        }
    }
}
