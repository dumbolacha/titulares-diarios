import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLConnection;
import java.text.Normalizer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lector de titulares RSS/Atom de varios periódicos.
 * - Descarga cada feed en un hilo (un fallo no detiene a los demás).
 * - Agrupa titulares que hablan de la misma historia.
 * - Ordena por número de fuentes que cubren la historia y, después, por fecha.
 *
 * Uso: java LectorRSS [config.properties]
 */
public class LectorRSS {

    // ---------- modelo ----------

    static final class Noticia {
        final String fuente, titulo, enlace;
        final Instant fecha;                 // null si el feed no trae una fecha válida
        final String imagen;                 // null si el feed no trae imagen
        final Set<String> palabras;          // palabras clave del titular, para comparar
        Noticia(String fuente, String titulo, String enlace, Instant fecha, String imagen) {
            this.fuente = fuente;
            this.titulo = titulo;
            this.enlace = enlace;
            this.fecha = fecha;
            this.imagen = imagen;
            this.palabras = palabras(titulo);
        }
    }

    static final class Grupo {           // titulares sobre la misma historia
        final List<Noticia> noticias = new ArrayList<>();

        Set<String> fuentes() {
            Set<String> s = new TreeSet<>();
            for (Noticia n : noticias) s.add(n.fuente);
            return s;
        }

        Instant fechaMax() {
            Instant max = Instant.EPOCH;
            for (Noticia n : noticias)
                if (n.fecha != null && n.fecha.isAfter(max)) max = n.fecha;
            return max;
        }

        Noticia principal() { return noticias.get(0); }   // la más reciente

        String imagen() {                                  // la primera versión que tenga foto
            for (Noticia n : noticias) if (n.imagen != null) return n.imagen;
            return null;
        }
    }

    // Palabras muy frecuentes que no ayudan a identificar una historia
    static final Set<String> VACIAS = Set.of(
            "para", "como", "sobre", "tras", "este", "esta", "estos", "estas",
            "contra", "desde", "entre", "hasta", "pero", "todo", "todos", "ante",
            "bajo", "donde", "cuando");

    // ---------- configuración ----------

    static Properties cargarConfig(String ruta) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(ruta)) {
            p.load(in);
        }
        return p;
    }

    // ---------- descarga y lectura del XML ----------

    static DocumentBuilder nuevoParser() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        // Un feed no debería traer DOCTYPE. Si lo trae, se rechaza (protege contra XXE).
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder();
    }

    static List<Noticia> leerFeed(String nombre, String url) throws Exception {
        URLConnection con = new URL(url).openConnection();
        con.setRequestProperty("User-Agent", "LectorRSSPersonal/1.0");
        con.setConnectTimeout(10_000);
        con.setReadTimeout(10_000);

        Document doc;
        try (InputStream in = con.getInputStream()) {
            doc = nuevoParser().parse(in);
        }

        XPath xp = XPathFactory.newInstance().newXPath();
        List<Noticia> out = new ArrayList<>();

        NodeList items = (NodeList) xp.evaluate("//item", doc, XPathConstants.NODESET);
        if (items.getLength() > 0) {                                   // RSS 2.0
            for (int i = 0; i < items.getLength(); i++) {
                Element it = (Element) items.item(i);
                String titulo = xp.evaluate("title", it).trim();
                String enlace = xp.evaluate("link", it).trim();
                Instant fecha = fechaRss(xp.evaluate("pubDate", it).trim());
                String imagen = imagenRss(xp, it);
                if (!titulo.isEmpty()) out.add(new Noticia(nombre, titulo, enlace, fecha, imagen));
            }
        } else {                                                       // Atom
            NodeList entries = (NodeList) xp.evaluate("//entry", doc, XPathConstants.NODESET);
            for (int i = 0; i < entries.getLength(); i++) {
                Element en = (Element) entries.item(i);
                String titulo = xp.evaluate("title", en).trim();
                String enlace = xp.evaluate("(link[not(@rel) or @rel='alternate']/@href)[1]", en).trim();
                String fechaTxt = xp.evaluate("updated", en).trim();
                if (fechaTxt.isEmpty()) fechaTxt = xp.evaluate("published", en).trim();
                if (!titulo.isEmpty())
                    out.add(new Noticia(nombre, titulo, enlace, fechaAtom(fechaTxt), imagenAtom(xp, en)));
            }
        }
        return out;
    }

    static Instant fechaRss(String s) {              // ej.: "Fri, 13 Nov 2026 12:00:00 +0100"
        if (s.isEmpty()) return null;
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Instant fechaAtom(String s) {             // ej.: "2026-11-13T12:30:00+01:00"
        if (s.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---------- imagen de cada noticia ----------

    // Cada periódico publica la foto de una forma distinta; se prueban en este orden
    static final String[] XPATH_IMAGEN = {
        "*[name()='media:content' and (not(@medium) or @medium='image') and (not(@type) or starts-with(@type,'image'))]/@url",
        "*[name()='media:group']/*[name()='media:content' and (not(@medium) or @medium='image')]/@url",
        "*[name()='media:thumbnail']/@url",
        "*[name()='media:content']/*[name()='media:thumbnail']/@url",
        "enclosure[starts-with(@type,'image')]/@url"
    };
    static final Pattern IMG_HTML = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    static String imagenRss(XPath xp, Element item) throws XPathExpressionException {
        for (String expr : XPATH_IMAGEN) {
            String url = xp.evaluate("(" + expr + ")[1]", item).trim();
            if (esUrlWeb(url)) return url;
        }
        // último recurso: la primera <img> dentro del resumen o del contenido
        for (String campo : new String[]{"*[name()='content:encoded']", "description", "content", "summary"}) {
            Matcher m = IMG_HTML.matcher(xp.evaluate(campo, item));
            if (m.find()) {
                String url = m.group(1).replace("&amp;", "&").trim();
                if (esUrlWeb(url)) return url;
            }
        }
        return null;
    }

    static String imagenAtom(XPath xp, Element entry) throws XPathExpressionException {
        String url = xp.evaluate("(link[@rel='enclosure' and starts-with(@type,'image')]/@href)[1]", entry).trim();
        if (esUrlWeb(url)) return url;
        return imagenRss(xp, entry);          // media:* y <img> en el contenido se buscan igual
    }

    static boolean esUrlWeb(String s) {
        return s != null && (s.startsWith("https://") || s.startsWith("http://"));
    }

    // ---------- comparación de titulares ----------

    static Set<String> palabras(String titulo) {
        // minúsculas y sin tildes: "Congreso aprobó" y "congreso aprobo" son iguales
        String s = Normalizer.normalize(titulo.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                             .replaceAll("\\p{M}", "");
        Set<String> out = new HashSet<>();
        for (String p : s.split("[^a-z0-9]+")) {
            if (p.length() > 3 && !VACIAS.contains(p)) out.add(p);
        }
        return out;
    }

    // Índice de Jaccard: palabras en común / palabras totales (0 a 1)
    static double similaridade(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> uni = new HashSet<>(a);
        uni.addAll(b);
        return (double) inter.size() / uni.size();
    }

    static boolean pertenece(Grupo g, Noticia n, double umbral) {
        for (Noticia m : g.noticias)                 // basta parecerse a algún miembro
            if (similaridade(n.palabras, m.palabras) >= umbral) return true;
        return false;
    }

    static List<Grupo> agrupar(List<Noticia> noticias, double umbral) {
        List<Noticia> ordenadas = new ArrayList<>(noticias);
        ordenadas.sort(Comparator.comparing(
                (Noticia n) -> n.fecha == null ? Instant.EPOCH : n.fecha).reversed());

        List<Grupo> grupos = new ArrayList<>();
        for (Noticia n : ordenadas) {
            Grupo destino = null;
            for (Grupo g : grupos) {
                if (pertenece(g, n, umbral)) { destino = g; break; }
            }
            if (destino == null) {
                destino = new Grupo();
                grupos.add(destino);
            }
            destino.noticias.add(n);
        }

        // Primero las historias con más fuentes; empate: la más reciente
        grupos.sort((a, b) -> {
            int c = Integer.compare(b.fuentes().size(), a.fuentes().size());
            return c != 0 ? c : b.fechaMax().compareTo(a.fechaMax());
        });
        return grupos;
    }

    // ---------- salida en JSON (para la página web) ----------

    static final class FeedInfo {
        final String nombre, error;
        final int titulares;
        FeedInfo(String nombre, int titulares, String error) {
            this.nombre = nombre; this.titulares = titulares; this.error = error;
        }
    }

    // Escapa un texto para que sea un string JSON válido
    static String texto(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    static void escribirJson(String ruta, List<FeedInfo> infos, List<Grupo> grupos,
                             List<Noticia> todas, int top) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"actualizado\": ").append(texto(Instant.now().toString())).append(",\n");

        // estado de cada feed
        sb.append("  \"feeds\": [\n");
        for (int i = 0; i < infos.size(); i++) {
            FeedInfo f = infos.get(i);
            sb.append("    {\"nombre\": ").append(texto(f.nombre))
              .append(", \"titulares\": ").append(f.titulares)
              .append(", \"error\": ").append(f.error == null ? "null" : texto(f.error))
              .append(i < infos.size() - 1 ? "},\n" : "}\n");
        }
        sb.append("  ],\n");

        // última hora: las noticias más recientes de todos los feeds
        List<Noticia> recientes = new ArrayList<>(todas);
        recientes.sort(Comparator.comparing(
                (Noticia n) -> n.fecha == null ? Instant.EPOCH : n.fecha).reversed());
        int lim = Math.min(top, recientes.size());
        sb.append("  \"ultimaHora\": [\n");
        for (int i = 0; i < lim; i++) {
            Noticia n = recientes.get(i);
            sb.append("    {\"titulo\": ").append(texto(n.titulo))
              .append(", \"enlace\": ").append(texto(n.enlace))
              .append(", \"fuente\": ").append(texto(n.fuente))
              .append(", \"fecha\": ").append(n.fecha == null ? "null" : texto(n.fecha.toString()))
              .append(", \"imagen\": ").append(n.imagen == null ? "null" : texto(n.imagen))
              .append(i < lim - 1 ? "},\n" : "}\n");
        }
        sb.append("  ],\n");

        // historias con más fuentes, con el titular de cada medio
        int hl = Math.min(top, grupos.size());
        sb.append("  \"historias\": [\n");
        for (int i = 0; i < hl; i++) {
            Grupo g = grupos.get(i);
            Noticia p = g.principal();
            sb.append("    {\"titulo\": ").append(texto(p.titulo))
              .append(", \"enlace\": ").append(texto(p.enlace))
              .append(", \"numFuentes\": ").append(g.fuentes().size())
              .append(", \"fecha\": ").append(p.fecha == null ? "null" : texto(p.fecha.toString()))
              .append(", \"imagen\": ").append(g.imagen() == null ? "null" : texto(g.imagen()))
              .append(", \"versiones\": [");
            int k = 0;
            for (Noticia v : g.noticias) {
                if (k++ > 0) sb.append(", ");
                sb.append("{\"fuente\": ").append(texto(v.fuente))
                  .append(", \"titulo\": ").append(texto(v.titulo))
                  .append(", \"enlace\": ").append(texto(v.enlace)).append("}");
            }
            sb.append("]")
              .append(i < hl - 1 ? "},\n" : "}\n");
        }
        sb.append("  ]\n}\n");

        try {
            Files.writeString(Path.of(ruta), sb.toString(), StandardCharsets.UTF_8);
            System.out.println("JSON guardado en: " + Path.of(ruta).toAbsolutePath());
            // Mismo contenido como script: así la página funciona con doble clic, sin servidor
            Path rutaJs = Path.of(ruta.replaceAll("\\.json$", "") + ".js");
            Files.writeString(rutaJs, "window.NOTICIAS = " + sb.toString().trim() + ";\n", StandardCharsets.UTF_8);
            System.out.println("JS guardado en: " + rutaJs.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("No se pudo guardar el JSON: " + e.getMessage());
        }
    }

    // ---------- ejecución ----------

    static void ejecutar(Map<String, String> feeds, double umbral, int top, String salida) {
        if (feeds.isEmpty()) {
            System.out.println("No hay feeds en la configuración (líneas feed.<nombre>=<url>).");
            return;
        }
        System.out.println("Actualizado: " + ZonedDateTime.now());

        List<String> nombres = new ArrayList<>(feeds.keySet());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, nombres.size()));
        List<Future<List<Noticia>>> futuros = new ArrayList<>();
        for (String nombre : nombres) {
            String url = feeds.get(nombre);
            futuros.add(pool.submit(() -> leerFeed(nombre, url)));
        }

        List<Noticia> todas = new ArrayList<>();
        List<FeedInfo> infos = new ArrayList<>();
        for (int i = 0; i < futuros.size(); i++) {
            String nombre = nombres.get(i);
            try {
                List<Noticia> l = futuros.get(i).get(30, TimeUnit.SECONDS);
                System.out.printf("OK     %-10s %3d titulares%n", nombre, l.size());
                todas.addAll(l);
                infos.add(new FeedInfo(nombre, l.size(), null));
            } catch (ExecutionException e) {
                System.out.printf("FALLO  %-10s %s%n", nombre, e.getCause());
                infos.add(new FeedInfo(nombre, 0, String.valueOf(e.getCause())));
            } catch (TimeoutException e) {
                futuros.get(i).cancel(true);
                System.out.printf("TIEMPO %-10s no respondió%n", nombre);
                infos.add(new FeedInfo(nombre, 0, "tiempo agotado"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        pool.shutdownNow();

        List<Grupo> grupos = agrupar(todas, umbral);
        escribirJson(salida, infos, grupos, todas, top);
        System.out.printf("%n=== Top %d historias (%d titulares de %d feeds) ===%n",
                Math.min(top, grupos.size()), todas.size(), feeds.size());

        int n = 0;
        for (Grupo g : grupos) {
            if (++n > top) break;
            Noticia p = g.principal();
            System.out.printf("%2d. [%d fuente(s): %s]%n", n, g.fuentes().size(),
                    String.join(", ", g.fuentes()));
            System.out.printf("    %s%n", p.titulo);
            System.out.printf("    %s%n", p.enlace);
        }
    }

    public static void main(String[] args) throws Exception {
        String ruta = args.length > 0 ? args[0] : "config.properties";
        Properties cfg = cargarConfig(ruta);

        Map<String, String> feeds = new TreeMap<>();
        for (String clave : cfg.stringPropertyNames()) {
            if (clave.startsWith("feed.")) feeds.put(clave.substring(5), cfg.getProperty(clave).trim());
        }
        double umbral = Double.parseDouble(cfg.getProperty("similaridad", "0.3"));
        int top = Integer.parseInt(cfg.getProperty("top", "10"));
        int minutos = Integer.parseInt(cfg.getProperty("intervalo.minutos", "0"));
        String salida = cfg.getProperty("salida", "noticias.json");

        if (minutos <= 0) {
            ejecutar(feeds, umbral, top, salida);
        } else {
            ScheduledExecutorService reloj = Executors.newSingleThreadScheduledExecutor();
            reloj.scheduleAtFixedRate(() -> {
                try {
                    ejecutar(feeds, umbral, top, salida);
                } catch (RuntimeException e) {
                    // si no se captura, el planificador deja de repetir la tarea
                    e.printStackTrace();
                }
            }, 0, minutos, TimeUnit.MINUTES);
            new CountDownLatch(1).await();           // mantiene vivo el programa; Ctrl+C para salir
        }
    }
}
