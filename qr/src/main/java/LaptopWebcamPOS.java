import com.formdev.flatlaf.FlatLightLaf;
import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamPanel;
import com.github.sarxos.webcam.WebcamResolution;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterJob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class LaptopWebcamPOS {

    // ================== SETTINGS (change your store details here) ==================
    private static final String STORE_NAME = "SIR JOEY STORE";
    private static final String STORE_ADDRESS = "Davao City, Philippines";
    private static final String EMPLOYEE = "Nilben";
    private static final String POS_NAME = "POS 1";
    private static final String PAYMENT_LABEL = "Cash";   // shown on the receipt before the amount paid (e.g. "Cash" or "GCash / QR Pay")
    private static final int RECEIPT_WIDTH = 32;      // 58mm printer = 32, 80mm printer = 48
    private static final String PRINTER_NAME = "POS-58";   // part of the name is enough (case-insensitive). Empty = default printer
    // false = normal printing (works with PDF printers and most thermal drivers)
    // true  = raw ESC/POS bytes (only for real thermal printers that accept them)
    private static final boolean USE_RAW_ESCPOS = true;
    // true = shows the Windows print dialog so you can pick the printer (handy while testing)
    private static final boolean SHOW_PRINT_DIALOG = true;

    // ================== PRODUCT LIST ==================
    // The key is what must be written inside the QR code (case-insensitive).
    static class Product {
        final String name;
        final double price;
        Product(String name, double price) { this.name = name; this.price = price; }
    }

    // The list is read from products.csv (in the folder where the program runs).
    // Format per line:  code,name,price     e.g.  SARDINAS,Sardinas 155g,30.00
    private static final String PRODUCTS_FILE = "products.csv";
    private static final Map<String, Product> CATALOG = new LinkedHashMap<>();

    // ================== COLORS ==================
    private static final Color NAVY = new Color(0x1F2A44);
    private static final Color GREEN = new Color(0x2E9E5B);
    private static final Color BLUE = new Color(0x2563EB);
    private static final Color RED = new Color(0xD64545);
    private static final Color GRAY = new Color(0x6B7280);
    private static final Color LIGHT = new Color(0xF3F4F6);

    // ================== STATE ==================
    private static JFrame frame;
    private static DefaultTableModel tableModel;
    private static DefaultTableModel productsModel;
    private static JLabel statusLabel;
    private static JLabel totalLabel;
    private static JPanel webcamContainer;
    private static JButton startButton;
    private static JTextField paymentField;
    private static JLabel changeLabel;
    private static Webcam webcam;
    private static WebcamPanel webcamPanel;
    private static volatile boolean isScanning = false;

    private static final Map<String, Integer> cart = new LinkedHashMap<>();
    private static String lastCode = null;
    private static int receiptNo = 1015;

    public static void main(String[] args) {
        FlatLightLaf.setup();
        UIManager.put("Button.arc", 16);
        UIManager.put("Component.arc", 12);
        UIManager.put("TextComponent.arc", 12);
        SwingUtilities.invokeLater(LaptopWebcamPOS::buildUi);
    }

    // ================== UI ==================
    private static void buildUi() {
        String loadMsg = loadProducts();
        frame = new JFrame(STORE_NAME + " - POS");
        frame.setSize(1280, 790);
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);   // fit the screen so the bottom is not cut off
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // Header + status
        JLabel title = new JLabel(STORE_NAME);
        title.setFont(new Font("Segoe UI", Font.BOLD, 24));
        title.setForeground(Color.WHITE);
        title.setOpaque(true);
        title.setBackground(NAVY);
        title.setBorder(new EmptyBorder(14, 20, 14, 20));

        statusLabel = new JLabel("", SwingConstants.CENTER);
        statusLabel.setOpaque(true);
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 15));
        statusLabel.setBorder(new EmptyBorder(9, 10, 9, 10));
        setStatus(loadMsg, loadMsg.startsWith("Loaded") ? GRAY : RED);

        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(title);
        top.add(statusLabel);
        frame.add(top, BorderLayout.NORTH);

        // Left: camera
        webcamContainer = new JPanel(new BorderLayout());
        webcamContainer.setBackground(Color.BLACK);
        showPlaceholder();

        // Right: cart table
        tableModel = new DefaultTableModel(new String[]{"Item", "Price", "Qty", "Subtotal"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(tableModel);
        table.setRowHeight(38);
        table.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        table.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 14));
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(0xE5E7EB));
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c = 1; c <= 3; c++) table.getColumnModel().getColumn(c).setCellRenderer(right);
        table.getColumnModel().getColumn(0).setPreferredWidth(200);

        JButton undoButton = makeButton("Remove last item", GRAY, 14);
        JButton clearButton = makeButton("New sale (Clear)", GRAY, 14);
        undoButton.addActionListener(e -> { removeLastItem(); refreshCart(); });
        clearButton.addActionListener(e -> newTransaction());
        JPanel tools = new JPanel(new GridLayout(1, 2, 10, 0));
        tools.add(undoButton);
        tools.add(clearButton);

        JPanel rightPanel = new JPanel(new BorderLayout(0, 10));
        rightPanel.add(new JScrollPane(table), BorderLayout.CENTER);
        rightPanel.add(tools, BorderLayout.SOUTH);

        JPanel center = new JPanel(new GridLayout(1, 2, 16, 0));
        center.setBorder(new EmptyBorder(16, 16, 8, 16));
        center.add(webcamContainer);
        center.add(rightPanel);
        JPanel salesPage = new JPanel(new BorderLayout());   // first tab: Sales
        salesPage.add(center, BorderLayout.CENTER);

        // Bottom: big total + buttons
        JLabel caption = new JLabel("TOTAL");
        caption.setFont(new Font("Segoe UI", Font.BOLD, 14));
        caption.setForeground(GRAY);
        totalLabel = new JLabel("₱0.00");
        totalLabel.setFont(new Font("Segoe UI", Font.BOLD, 46));
        totalLabel.setForeground(NAVY);
        JPanel totalBox = new JPanel(new GridLayout(2, 1));
        totalBox.add(caption);
        totalBox.add(totalLabel);

        startButton = makeButton("Start Scanning", GREEN, 16);
        JButton printButton = makeButton("Print Receipt", BLUE, 16);
        startButton.addActionListener(e -> toggleScanner());
        printButton.addActionListener(e -> printReceiptAction());
        JPanel actions = new JPanel(new GridLayout(1, 2, 12, 0));
        actions.setPreferredSize(new Dimension(460, 68));
        actions.add(startButton);
        actions.add(printButton);

        // Customer payment and change
        JLabel payCaption = new JLabel("CUSTOMER PAYMENT (₱)");
        payCaption.setFont(new Font("Segoe UI", Font.BOLD, 14));
        payCaption.setForeground(GRAY);
        JLabel changeCaption = new JLabel("CHANGE");
        changeCaption.setFont(new Font("Segoe UI", Font.BOLD, 14));
        changeCaption.setForeground(GRAY);

        paymentField = new JTextField();
        paymentField.setFont(new Font("Segoe UI", Font.BOLD, 30));
        paymentField.setHorizontalAlignment(SwingConstants.RIGHT);
        paymentField.putClientProperty("JTextField.placeholderText", "0.00");
        paymentField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateChange(); }
            @Override public void removeUpdate(DocumentEvent e) { updateChange(); }
            @Override public void changedUpdate(DocumentEvent e) { updateChange(); }
        });
        paymentField.addActionListener(e -> printReceiptAction());   // Enter = print

        changeLabel = new JLabel("₱0.00");
        changeLabel.setFont(new Font("Segoe UI", Font.BOLD, 30));
        changeLabel.setForeground(GRAY);

        JPanel payGrid = new JPanel(new GridLayout(2, 2, 14, 2));
        payGrid.add(payCaption);
        payGrid.add(changeCaption);
        payGrid.add(paymentField);
        payGrid.add(changeLabel);

        JPanel quick = new JPanel(new GridLayout(1, 4, 6, 0));
        quick.add(quickButton("Exact", -1));
        for (int amt : new int[]{100, 500, 1000}) quick.add(quickButton("₱" + amt, amt));

        JPanel paymentPanel = new JPanel(new BorderLayout(0, 6));
        paymentPanel.add(payGrid, BorderLayout.CENTER);
        paymentPanel.add(quick, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(24, 0));
        bottom.setBorder(new EmptyBorder(8, 20, 16, 16));
        bottom.add(totalBox, BorderLayout.WEST);
        bottom.add(paymentPanel, BorderLayout.CENTER);
        bottom.add(actions, BorderLayout.EAST);
        salesPage.add(bottom, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("Segoe UI", Font.BOLD, 15));
        tabs.addTab("   Sales   ", salesPage);
        tabs.addTab("   Products & QR   ", buildProductsPage());
        frame.add(tabs, BorderLayout.CENTER);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static JButton makeButton(String text, Color bg, int size) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("Segoe UI", Font.BOLD, size));
        b.setFocusPainted(false);
        return b;
    }

    private static void showPlaceholder() {
        webcamContainer.removeAll();
        JLabel l = new JLabel("Camera is off", SwingConstants.CENTER);
        l.setForeground(Color.WHITE);
        l.setFont(new Font("Segoe UI", Font.PLAIN, 16));
        webcamContainer.add(l, BorderLayout.CENTER);
        webcamContainer.revalidate();
        webcamContainer.repaint();
    }

    private static void setStatus(String msg, Color bg) {
        statusLabel.setText(msg);
        statusLabel.setBackground(bg);
        statusLabel.setForeground(Color.WHITE);
    }

    // ================== SCANNER ==================
    private static void toggleScanner() {
        if (isScanning) stopScanner(); else startScanner();
    }

    private static void startScanner() {
        startButton.setEnabled(false);
        setStatus("Opening camera...", GRAY);

        new Thread(() -> {
            try {
                webcam = Webcam.getDefault();
                if (webcam == null) {
                    SwingUtilities.invokeLater(() -> {
                        setStatus("Error: No webcam found!", RED);
                        startButton.setEnabled(true);
                    });
                    return;
                }
                webcam.setViewSize(WebcamResolution.VGA.getSize());
                webcamPanel = new WebcamPanel(webcam);
                webcamPanel.setMirrored(true);
                isScanning = true;

                SwingUtilities.invokeLater(() -> {
                    webcamContainer.removeAll();
                    webcamContainer.add(webcamPanel, BorderLayout.CENTER);
                    webcamContainer.revalidate();
                    webcamContainer.repaint();
                    startButton.setText("Stop Scanning");
                    startButton.setBackground(RED);
                    startButton.setEnabled(true);
                    setStatus("Ready. Hold a QR code up to the camera.", GREEN);
                });

                while (isScanning) {
                    BufferedImage image = webcam.getImage();
                    String text = (image == null) ? null : decodeQRCode(image);
                    if (text != null) {
                        final String code = text.trim();
                        SwingUtilities.invokeLater(() -> handleScan(code));
                        Thread.sleep(1500);   // pause so the same scan is not counted twice
                    } else {
                        Thread.sleep(120);
                    }
                }
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                isScanning = false;
                SwingUtilities.invokeLater(() -> {
                    setStatus("Camera error: " + msg, RED);
                    startButton.setText("Start Scanning");
                    startButton.setBackground(GREEN);
                    startButton.setEnabled(true);
                });
            }
        }, "scanner").start();
    }

    private static void stopScanner() {
        isScanning = false;
        try {
            if (webcamPanel != null) webcamPanel.stop();
            if (webcam != null && webcam.isOpen()) webcam.close();
        } catch (Exception ignored) { }
        showPlaceholder();
        startButton.setText("Start Scanning");
        startButton.setBackground(GREEN);
        setStatus("Scanner paused", GRAY);
    }

    private static String decodeQRCode(BufferedImage image) {
        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result result = new MultiFormatReader().decode(bitmap, hints);
            return result.getText();
        } catch (NotFoundException e) {
            return null;
        }
    }

    // ================== CART ==================
    private static void handleScan(String raw) {
        String key = raw.trim().toUpperCase();
        Product p = CATALOG.get(key);
        if (p == null) {
            Toolkit.getDefaultToolkit().beep();
            String shown = raw.length() > 30 ? raw.substring(0, 30) + "..." : raw;
            setStatus("Not a product QR code: " + shown, RED);
            return;
        }
        Toolkit.getDefaultToolkit().beep();
        Integer qty = cart.get(key);
        cart.put(key, qty == null ? 1 : qty + 1);
        lastCode = key;
        refreshCart();
        setStatus("Scanned: " + p.name + "  (₱" + String.format("%.2f", p.price) + ")", GREEN);
    }

    private static void removeLastItem() {
        String target = lastCode;
        if (target == null || !cart.containsKey(target)) {
            target = null;
            for (String k : cart.keySet()) target = k;
        }
        if (target == null) return;
        int q = cart.get(target) - 1;
        if (q <= 0) cart.remove(target); else cart.put(target, q);
        lastCode = null;
    }

    private static double total() {
        double t = 0;
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            t += CATALOG.get(e.getKey()).price * e.getValue();
        }
        return t;
    }

    private static void refreshCart() {
        tableModel.setRowCount(0);
        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            Product p = CATALOG.get(e.getKey());
            int qty = e.getValue();
            tableModel.addRow(new Object[]{
                    p.name,
                    String.format("₱%.2f", p.price),
                    qty,
                    String.format("₱%.2f", p.price * qty)
            });
        }
        totalLabel.setText(String.format("₱%.2f", total()));
        updateChange();
    }

    private static void newTransaction() {
        cart.clear();
        lastCode = null;
        if (paymentField != null) paymentField.setText("");
        refreshCart();
    }

    // ================== TAB: PRODUCTS & QR ==================
    private static JPanel buildProductsPage() {
        productsModel = new DefaultTableModel(new String[]{"Code (in QR)", "Name", "Price"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable t = new JTable(productsModel);
        t.setRowHeight(34);
        t.setFont(new Font("Segoe UI", Font.PLAIN, 15));
        t.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 14));
        t.setShowHorizontalLines(true);
        t.setGridColor(new Color(0xE5E7EB));
        DefaultTableCellRenderer rightAlign = new DefaultTableCellRenderer();
        rightAlign.setHorizontalAlignment(SwingConstants.RIGHT);
        t.getColumnModel().getColumn(2).setCellRenderer(rightAlign);
        t.getColumnModel().getColumn(1).setPreferredWidth(300);

        JLabel info = new JLabel("<html>This list comes from <b>products.csv</b>. "
                + "Edit the file (Excel or Notepad), <b>save and close it</b>, then press "
                + "<b>Reload products</b>. <b>Generate product QR codes</b> creates an image for every product.</html>");
        info.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        info.setForeground(GRAY);

        JButton reload = makeButton("Reload products", BLUE, 15);
        JButton qr = makeButton("Generate product QR codes", GREEN, 15);
        JButton openCsv = makeButton("Open products.csv", GRAY, 15);
        JButton openQr = makeButton("Open QR folder", GRAY, 15);
        reload.addActionListener(e -> reloadProducts());
        qr.addActionListener(e -> generateQrAction());
        openCsv.addActionListener(e -> openInDesktop(Paths.get(PRODUCTS_FILE).toAbsolutePath(),
                "products.csv does not exist yet"));
        openQr.addActionListener(e -> openInDesktop(Paths.get("qr_codes").toAbsolutePath(),
                "The QR folder does not exist yet. Press 'Generate product QR codes'"));

        JPanel buttons = new JPanel(new GridLayout(2, 2, 12, 12));
        buttons.setPreferredSize(new Dimension(100, 130));
        buttons.add(reload);
        buttons.add(qr);
        buttons.add(openCsv);
        buttons.add(openQr);

        JPanel page = new JPanel(new BorderLayout(0, 12));
        page.setBorder(new EmptyBorder(16, 16, 16, 16));
        page.add(info, BorderLayout.NORTH);
        page.add(new JScrollPane(t), BorderLayout.CENTER);
        page.add(buttons, BorderLayout.SOUTH);

        refreshProductsTable();
        return page;
    }

    private static void refreshProductsTable() {
        if (productsModel == null) return;
        productsModel.setRowCount(0);
        for (Map.Entry<String, Product> e : CATALOG.entrySet()) {
            productsModel.addRow(new Object[]{
                    e.getKey(), e.getValue().name, String.format("₱%.2f", e.getValue().price)});
        }
    }

    private static void openInDesktop(Path path, String missingMsg) {
        if (!Files.exists(path)) {
            setStatus(missingMsg, RED);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(path.toFile());
        } catch (Exception ex) {
            setStatus("Could not open: " + ex.getMessage(), RED);
        }
    }

    // ================== PRODUCTS.CSV ==================
    // Reads products.csv and replaces the contents of CATALOG.
    // If the file does not exist yet, a sample is created. Returns the message shown in the status bar.
    private static String loadProducts() {
        Path path = Paths.get(PRODUCTS_FILE).toAbsolutePath();
        try {
            if (!Files.exists(path)) {
                List<String> sample = new ArrayList<>();
                sample.add("\uFEFFcode,name,price");
                sample.add("SARDINAS,Sardinas 155g,30.00");
                sample.add("KAPE,Kape 3in1,8.00");
                sample.add("BIGAS1KG,Bigas 1kg,55.00");
                sample.add("PANCIT,Pancit Canton,18.00");
                sample.add("ITLOG,Itlog (1pc),10.00");
                Files.write(path, sample, StandardCharsets.UTF_8);
            }

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            Map<String, Product> loaded = new LinkedHashMap<>();
            int skipped = 0;

            for (String raw : lines) {
                String line = raw.replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                char delim = line.indexOf(';') >= 0 ? ';' : ',';   // Excel in some regions uses ;
                List<String> cols = splitCsv(line, delim);
                if (cols.size() < 3) { skipped++; continue; }

                String code = cols.get(0).trim().toUpperCase();
                String name = cols.get(1).trim();
                if (code.equals("CODE")) continue;                  // header line

                String priceText = cols.get(2).replace("₱", "").replace("PHP", "").replace("php", "").trim();
                priceText = (delim == ';') ? priceText.replace(',', '.') : priceText.replace(",", "");
                try {
                    double price = Double.parseDouble(priceText);
                    if (code.isEmpty() || name.isEmpty() || price < 0) throw new NumberFormatException();
                    loaded.put(code, new Product(name, round2(price)));
                } catch (NumberFormatException e) {
                    skipped++;
                }
            }

            if (loaded.isEmpty()) {
                return "No valid products in " + path + " (product list unchanged)";
            }
            CATALOG.clear();
            CATALOG.putAll(loaded);
            refreshProductsTable();
            return "Loaded " + loaded.size() + " products from " + path
                    + (skipped > 0 ? "  |  " + skipped + " line(s) skipped (invalid format)" : "");
        } catch (IOException ex) {
            return "Could not read " + path + ": " + ex.getMessage();
        }
    }

    // For the "Reload products" button: no restart needed.
    private static void reloadProducts() {
        String msg = loadProducts();
        cart.keySet().retainAll(CATALOG.keySet());   // remove cart items whose code is no longer in the list
        if (lastCode != null && !CATALOG.containsKey(lastCode)) lastCode = null;
        refreshCart();                               // new prices are picked up automatically
        setStatus(msg, msg.startsWith("Loaded") ? GREEN : RED);
    }

    // Simple CSV splitter that can read "values, with commas" inside quotes.
    private static List<String> splitCsv(String line, char delim) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delim && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    // ================== GENERATE PRODUCT QR CODES ==================
    // Creates qr_<CODE>.png per product and QR_sheet_N.png (12 per page) in the "qr_codes" folder.
    private static void generateQrAction() {
        reloadProducts();   // make sure products.csv is up to date
        if (CATALOG.isEmpty()) {
            setStatus("No products to generate QR codes for", RED);
            return;
        }
        setStatus("Generating QR codes...", GRAY);
        final Map<String, Product> snapshot = new LinkedHashMap<>(CATALOG);

        new Thread(() -> {
            try {
                Path dir = Paths.get("qr_codes").toAbsolutePath();
                Files.createDirectories(dir);

                List<BufferedImage> cards = new ArrayList<>();
                for (Map.Entry<String, Product> e : snapshot.entrySet()) {
                    BufferedImage card = makeQrCard(e.getKey(), e.getValue());
                    String safe = e.getKey().replaceAll("[^A-Z0-9_-]", "_");
                    ImageIO.write(card, "PNG", dir.resolve("qr_" + safe + ".png").toFile());
                    cards.add(card);
                }
                int sheets = writeQrSheets(cards, dir);

                final Path outDir = dir;
                final String msg = "Generated " + cards.size() + " QR codes and " + sheets + " sheet(s) in " + dir;
                SwingUtilities.invokeLater(() -> {
                    setStatus(msg, GREEN);
                    try {
                        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(outDir.toFile());
                    } catch (Exception ignored) { }
                });
            } catch (Exception ex) {
                final String err = ex.getMessage();
                SwingUtilities.invokeLater(() -> setStatus("Could not generate QR codes: " + err, RED));
            }
        }, "qr-generator").start();
    }

    // One card: QR + name + price + code
    private static BufferedImage makeQrCard(String code, Product p) throws Exception {
        final int w = 440, h = 560, qr = 360;

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, qr, qr, hints);
        BufferedImage qrImg = MatrixToImageWriter.toBufferedImage(matrix);

        BufferedImage card = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = card.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x9CA3AF));
        g.setStroke(new BasicStroke(3));
        g.drawRect(1, 1, w - 3, h - 3);
        g.drawImage(qrImg, (w - qr) / 2, 25, null);

        drawCentered(g, p.name, new Font("Segoe UI", Font.BOLD, 30), Color.BLACK, w, 415);
        drawCentered(g, String.format("PHP %.2f", p.price), new Font("Segoe UI", Font.BOLD, 30), NAVY, w, 462);
        drawCentered(g, "Code: " + code, new Font("Segoe UI", Font.PLAIN, 20), GRAY, w, 508);
        g.dispose();
        return card;
    }

    // Draws the text centered; shrinks the font if it is too long
    private static void drawCentered(Graphics2D g, String text, Font font, Color color, int width, int baselineY) {
        Font f = font;
        FontMetrics fm = g.getFontMetrics(f);
        while (fm.stringWidth(text) > width - 40 && f.getSize() > 12) {
            f = f.deriveFont((float) (f.getSize() - 1));
            fm = g.getFontMetrics(f);
        }
        g.setFont(f);
        g.setColor(color);
        g.drawString(text, (width - fm.stringWidth(text)) / 2, baselineY);
    }

    // Combines the cards into printable pages: 3 columns, 12 cards per page
    private static int writeQrSheets(List<BufferedImage> cards, Path dir) throws IOException {
        final int cols = 3, perSheet = 12, pad = 30;
        int cw = cards.get(0).getWidth();
        int ch = cards.get(0).getHeight();
        int sheets = (cards.size() + perSheet - 1) / perSheet;

        for (int s = 0; s < sheets; s++) {
            int from = s * perSheet;
            int count = Math.min(cards.size(), from + perSheet) - from;
            int rows = (count + cols - 1) / cols;

            BufferedImage sheet = new BufferedImage(cols * cw + (cols + 1) * pad,
                    rows * ch + (rows + 1) * pad, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            for (int i = 0; i < count; i++) {
                int r = i / cols, c = i % cols;
                g.drawImage(cards.get(from + i), pad + c * (cw + pad), pad + r * (ch + pad), null);
            }
            g.dispose();
            ImageIO.write(sheet, "PNG", dir.resolve("QR_sheet_" + (s + 1) + ".png").toFile());
        }
        return sheets;
    }

    // ================== PAYMENT AND CHANGE ==================
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // Accepts "500", "1,000.50", "₱200". Returns null if empty or invalid.
    private static Double parseMoney(String s) {
        if (s == null) return null;
        String clean = s.replace("₱", "").replace(",", "").trim();
        if (clean.isEmpty()) return null;
        try {
            double v = Double.parseDouble(clean);
            return v < 0 ? null : round2(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void updateChange() {
        if (changeLabel == null || paymentField == null) return;
        Double paid = parseMoney(paymentField.getText());
        if (paid == null) {
            changeLabel.setText("₱0.00");
            changeLabel.setForeground(GRAY);
            return;
        }
        double diff = round2(paid - total());
        if (diff >= 0) {
            changeLabel.setText(String.format("₱%.2f", diff));
            changeLabel.setForeground(GREEN);
        } else {
            changeLabel.setText(String.format("Short ₱%.2f", -diff));
            changeLabel.setForeground(RED);
        }
    }

    // amount < 0 = "Exact" (fills in the exact total)
    private static JButton quickButton(String text, int amount) {
        JButton b = makeButton(text, GRAY, 13);
        b.addActionListener(e -> {
            double v = amount < 0 ? round2(total()) : amount;
            paymentField.setText(String.format("%.2f", v));
        });
        return b;
    }

    // ================== RECEIPT ==================
    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    private static String center(String s) {
        if (s.length() > RECEIPT_WIDTH) s = s.substring(0, RECEIPT_WIDTH);
        return repeat(' ', (RECEIPT_WIDTH - s.length()) / 2) + s + "\n";
    }

    private static String line(String left, String right) {
        int space = Math.max(1, RECEIPT_WIDTH - left.length() - right.length());
        return left + repeat(' ', space) + right + "\n";
    }

    private static String buildReceipt(double tendered) {
        DateTimeFormatter df = DateTimeFormatter.ofPattern("MM/dd/yy HH:mm");
        String dash = repeat('-', RECEIPT_WIDTH) + "\n";
        StringBuilder sb = new StringBuilder();

        sb.append(center(STORE_NAME));
        sb.append(center(STORE_ADDRESS));
        sb.append("\n");
        sb.append("Employee: ").append(EMPLOYEE).append("\n");
        sb.append("POS: ").append(POS_NAME).append("\n");
        sb.append(dash);

        for (Map.Entry<String, Integer> e : cart.entrySet()) {
            Product p = CATALOG.get(e.getKey());
            int qty = e.getValue();
            String name = p.name;
            if (name.length() > RECEIPT_WIDTH) name = name.substring(0, RECEIPT_WIDTH - 2) + "..";
            sb.append(name).append("\n");
            sb.append(line("  " + qty + " x P" + String.format("%.2f", p.price),
                           "P" + String.format("%.2f", p.price * qty)));
        }

        sb.append(dash);
        sb.append(line("Total", "P" + String.format("%.2f", total())));
        sb.append(line(PAYMENT_LABEL, "P" + String.format("%.2f", tendered)));
        sb.append(line("Change", "P" + String.format("%.2f", round2(tendered - total()))));
        sb.append(dash);
        sb.append(center("Thank you for shopping at"));
        sb.append(center(STORE_NAME + "!"));
        sb.append(center("Please come again!"));
        sb.append("\n");
        sb.append(line(LocalDateTime.now().format(df), String.format("#1-%04d", receiptNo)));
        return sb.toString();
    }

    private static void printReceiptAction() {
        if (cart.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "The cart is empty, nothing to print!",
                    "Warning", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String payText = paymentField.getText().trim();
        Double paid = parseMoney(payText);
        if (paid == null) {
            String msg = payText.isEmpty()
                    ? "Please enter the customer's payment."
                    : "Invalid payment amount: " + payText;
            JOptionPane.showMessageDialog(frame, msg, "Warning", JOptionPane.WARNING_MESSAGE);
            paymentField.requestFocus();
            return;
        }
        double due = round2(total());
        if (paid < due) {
            JOptionPane.showMessageDialog(frame,
                    String.format("Payment is not enough. Still short by ₱%.2f", due - paid),
                    "Insufficient Payment", JOptionPane.WARNING_MESSAGE);
            paymentField.requestFocus();
            return;
        }
        final double change = round2(paid - due);
        String receipt = buildReceipt(paid);

        JTextArea preview = new JTextArea(receipt);
        preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        preview.setEditable(false);
        JScrollPane sp = new JScrollPane(preview);
        sp.setPreferredSize(new Dimension(400, 460));

        int choice = JOptionPane.showOptionDialog(frame, sp, "Receipt Preview",
                JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null,
                new Object[]{"Print", "Cancel"}, "Print");
        if (choice != 0) return;

        try {
            if (!doPrint(receipt)) return;   // print dialog was cancelled, keep the cart
            receiptNo++;
            newTransaction();
            setStatus(String.format("Change: ₱%.2f  |  Sent to printer. Ready for the next customer.", change), GREEN);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Could not print: " + ex.getMessage(),
                    "Printer Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ================== PRINT (normal / graphic) ==================
    // Returns false if the user cancelled the print dialog.
    private static boolean doPrint(String text) throws Exception {
        if (USE_RAW_ESCPOS) {
            printThermal(text);
            return true;
        }
        return printGraphic(text);
    }

    private static boolean printGraphic(final String text) throws Exception {
        PrinterJob job = PrinterJob.getPrinterJob();

        if (!PRINTER_NAME.trim().isEmpty()) {
            for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
                if (s.getName().toLowerCase().contains(PRINTER_NAME.trim().toLowerCase())) job.setPrintService(s);
            }
        }
        if (SHOW_PRINT_DIALOG && !job.printDialog()) return false;

        final String[] lines = text.split("\n", -1);
        job.setJobName("Receipt");
        job.setPrintable((g, pf, pageIndex) -> {
            // font size: fit the full receipt width on the paper (max 11pt)
            float size = (float) Math.min(11.0, pf.getImageableWidth() / (RECEIPT_WIDTH * 0.6));
            Font font = new Font(Font.MONOSPACED, Font.PLAIN, 10).deriveFont(size);
            FontMetrics fm = g.getFontMetrics(font);
            int lh = fm.getHeight();
            int perPage = Math.max(1, (int) (pf.getImageableHeight() / lh));
            int start = pageIndex * perPage;
            if (start >= lines.length) return Printable.NO_SUCH_PAGE;

            Graphics2D g2 = (Graphics2D) g;
            g2.translate(pf.getImageableX(), pf.getImageableY());
            g2.setFont(font);
            g2.setColor(Color.BLACK);
            int y = fm.getAscent();
            for (int i = start; i < Math.min(lines.length, start + perPage); i++) {
                g2.drawString(lines[i], 0, y);
                y += lh;
            }
            return Printable.PAGE_EXISTS;
        });
        job.print();
        return true;
    }

    // ================== THERMAL PRINTER (ESC/POS raw bytes) ==================
    private static void printThermal(String text) throws Exception {
        PrintService ps = null;
        if (PRINTER_NAME.trim().isEmpty()) {
            ps = PrintServiceLookup.lookupDefaultPrintService();
        } else {
            for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
                if (s.getName().toLowerCase().contains(PRINTER_NAME.trim().toLowerCase())) ps = s;
            }
        }
        if (ps == null) {
            StringBuilder names = new StringBuilder();
            for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
                names.append("\n - ").append(s.getName());
            }
            throw new IllegalStateException("No printer found with a name containing '" + PRINTER_NAME
                    + "'. Printers on this computer:" + names);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x1B, 0x40});               // ESC @  : initialize
        out.write(text.getBytes("CP437"));
        out.write(new byte[]{'\n', '\n', '\n', '\n'});   // feed
        out.write(new byte[]{0x1D, 0x56, 0x42, 0x00});   // GS V B 0 : cut (if the printer has a cutter)

        DocPrintJob job = ps.createPrintJob();
        Doc doc = new SimpleDoc(out.toByteArray(), DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        job.print(doc, null);
    }
}
