package cz.mendelu.auto.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extrakce čistého textu z PDF souborů v {@code DOCLINKS}
 * (kmenová data Maximo, atributy {@code DOCNAME}, {@code URLNAME};
 * metadata na souborový server / S3).
 *
 * <p>Slouží jako vstupní pipeline pro pole {@code LONGDESCRIPTION},
 * kde je k~záznamu přiložen PDF s technickou dokumentací nebo
 * bezpečnostními pokyny. Extrahovaný text je následně předán
 * stejnému {@code TranslationOrchestrator} jako běžný řetězec.
 *
 * <p>Implementace používá Apache PDFBox 3.0 (open-source, Apache
 * License 2.0). Pro skenované PDF bez vrstvy textu je nezbytné
 * použít OCR (Tesseract/Azure Cognitive Services)~--- není součástí
 * tohoto modulu.
 *
 * <p><b>Upozornění:</b> tento modul provádí pouze extrakci textu;
 * mapování PDF formátování (nadpisy, tabulky, obrázky) na~HTML
 * není implementováno~--- přeložený výstup je plain text.
 */
@Slf4j
@Service
public class PdfTextExtractor {

    /**
     * Extrahuje veškerý text z PDF dokumentu.
     *
     * @param pdfBytes binární obsah PDF (např. stažený přes URL z DOCLINKS)
     * @return plain text dokumentu (textová vrstva)
     * @throws IOException při poškozeném nebo zašifrovaném PDF
     */
    public String extract(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return "";
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.isEncrypted()) {
                throw new IOException(
                        "Encrypted PDF — decryption is not supported in prototype");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);   // logické pořadí, ne stream
            stripper.setLineSeparator("\n");
            stripper.setParagraphStart("");
            String raw = stripper.getText(doc);
            return cleanup(raw);
        }
    }

    /** Pohodlnější varianta s {@link InputStream}. */
    public String extract(InputStream stream) throws IOException {
        return extract(stream.readAllBytes());
    }

    /** Načtení a~extrakce souboru z lokálního disku (pouze pro testy). */
    public String extractFromFile(Path path) throws IOException {
        return extract(Files.readAllBytes(path));
    }

    /**
     * Normalizace mezer a kompresí prázdných řádků.
     *
     * <p>PDFBox při extrakci zachovává původní layout, který může
     * obsahovat trailing whitespace na koncích řádků a~vícenásobné
     * mezery. Pro NMT vstup je vhodné normalizovat na~standardní
     * white-space.
     */
    public String cleanup(String text) {
        if (text == null) return "";
        // Odstraň trailing whitespace na řádcích
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean previousEmpty = false;
        for (String line : lines) {
            String trimmed = line.replaceAll("\\s+$", "")
                                 .replaceAll("\\s{2,}", " ");
            if (trimmed.isEmpty()) {
                if (!previousEmpty) sb.append('\n');
                previousEmpty = true;
            } else {
                sb.append(trimmed).append('\n');
                previousEmpty = false;
            }
        }
        return sb.toString().trim();
    }
}
