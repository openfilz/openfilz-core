package org.openfilz.dms.utils;

import org.openfilz.dms.enums.DocumentTemplateType;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Generates minimal blank Office documents (OOXML format) programmatically.
 * These are valid documents that can be opened and edited in OnlyOffice.
 */
@Component
public class BlankDocumentGenerator {

    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Generates a blank document of the specified type.
     *
     * @param documentType The type of document to generate
     * @return A byte array containing the blank document
     */
    public byte[] generateBlankDocument(DocumentTemplateType documentType) throws IOException {
        return switch (documentType) {
            case WORD -> generateBlankDocx();
            case EXCEL -> generateBlankXlsx();
            case POWERPOINT -> generateBlankPptx();
            case TEXT -> generateBlankTxt();
        };
    }

    private byte[] generateBlankTxt() {
        return EMPTY_BYTES;
    }

    private byte[] generateBlankDocx() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // [Content_Types].xml
            addEntry(zos, "[Content_Types].xml", DOCX_CONTENT_TYPES);
            // _rels/.rels
            addEntry(zos, "_rels/.rels", DOCX_RELS);
            // word/_rels/document.xml.rels
            addEntry(zos, "word/_rels/document.xml.rels", DOCX_DOCUMENT_RELS);
            // word/document.xml
            addEntry(zos, "word/document.xml", DOCX_DOCUMENT);
            // word/styles.xml
            addEntry(zos, "word/styles.xml", DOCX_STYLES);
        }
        return baos.toByteArray();
    }

    private byte[] generateBlankXlsx() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // [Content_Types].xml
            addEntry(zos, "[Content_Types].xml", XLSX_CONTENT_TYPES);
            // _rels/.rels
            addEntry(zos, "_rels/.rels", XLSX_RELS);
            // xl/_rels/workbook.xml.rels
            addEntry(zos, "xl/_rels/workbook.xml.rels", XLSX_WORKBOOK_RELS);
            // xl/workbook.xml
            addEntry(zos, "xl/workbook.xml", XLSX_WORKBOOK);
            // xl/worksheets/sheet1.xml
            addEntry(zos, "xl/worksheets/sheet1.xml", XLSX_SHEET);
            // xl/styles.xml
            addEntry(zos, "xl/styles.xml", XLSX_STYLES);
        }
        return baos.toByteArray();
    }

    private byte[] generateBlankPptx() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // [Content_Types].xml
            addEntry(zos, "[Content_Types].xml", PPTX_CONTENT_TYPES);
            // _rels/.rels
            addEntry(zos, "_rels/.rels", PPTX_RELS);
            // ppt/_rels/presentation.xml.rels
            addEntry(zos, "ppt/_rels/presentation.xml.rels", PPTX_PRESENTATION_RELS);
            // ppt/presentation.xml
            addEntry(zos, "ppt/presentation.xml", PPTX_PRESENTATION);
            // ppt/slides/_rels/slide1.xml.rels
            addEntry(zos, "ppt/slides/_rels/slide1.xml.rels", PPTX_SLIDE_RELS);
            // ppt/slides/slide1.xml
            addEntry(zos, "ppt/slides/slide1.xml", PPTX_SLIDE);
            // ppt/slideLayouts/_rels/slideLayout1.xml.rels
            addEntry(zos, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", PPTX_SLIDE_LAYOUT_RELS);
            // ppt/slideLayouts/slideLayout1.xml
            addEntry(zos, "ppt/slideLayouts/slideLayout1.xml", PPTX_SLIDE_LAYOUT);
            // ppt/slideMasters/_rels/slideMaster1.xml.rels
            addEntry(zos, "ppt/slideMasters/_rels/slideMaster1.xml.rels", PPTX_SLIDE_MASTER_RELS);
            // ppt/slideMasters/slideMaster1.xml
            addEntry(zos, "ppt/slideMasters/slideMaster1.xml", PPTX_SLIDE_MASTER);
            // ppt/theme/theme1.xml
            addEntry(zos, "ppt/theme/theme1.xml", PPTX_THEME);
        }
        return baos.toByteArray();
    }

    private void addEntry(ZipOutputStream zos, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // DOCX Templates
    private static final String DOCX_CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
            </Types>""";

    private static final String DOCX_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>""";

    private static final String DOCX_DOCUMENT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>""";

    private static final String DOCX_DOCUMENT = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body>
                    <w:p>
                        <w:r>
                            <w:t></w:t>
                        </w:r>
                    </w:p>
                </w:body>
            </w:document>""";

    private static final String DOCX_STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:docDefaults>
                    <w:rPrDefault>
                        <w:rPr>
                            <w:rFonts w:ascii="Calibri" w:hAnsi="Calibri"/>
                            <w:sz w:val="22"/>
                        </w:rPr>
                    </w:rPrDefault>
                </w:docDefaults>
            </w:styles>""";

    // XLSX Templates
    private static final String XLSX_CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
            </Types>""";

    private static final String XLSX_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
            </Relationships>""";

    private static final String XLSX_WORKBOOK_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
            </Relationships>""";

    private static final String XLSX_WORKBOOK = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets>
                    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
                </sheets>
            </workbook>""";

    private static final String XLSX_SHEET = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData/>
            </worksheet>""";

    private static final String XLSX_STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <fonts count="1">
                    <font>
                        <sz val="11"/>
                        <name val="Calibri"/>
                    </font>
                </fonts>
                <fills count="1">
                    <fill>
                        <patternFill patternType="none"/>
                    </fill>
                </fills>
                <borders count="1">
                    <border/>
                </borders>
                <cellStyleXfs count="1">
                    <xf/>
                </cellStyleXfs>
                <cellXfs count="1">
                    <xf/>
                </cellXfs>
            </styleSheet>""";

    // PPTX Templates
    private static final String PPTX_CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                <Default Extension="xml" ContentType="application/xml"/>
                <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
                <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
                <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
            </Types>""";

    private static final String PPTX_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
            </Relationships>""";

    private static final String PPTX_PRESENTATION_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
                <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
            </Relationships>""";

    private static final String PPTX_PRESENTATION = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <p:sldMasterIdLst>
                    <p:sldMasterId id="2147483648" r:id="rId1"/>
                </p:sldMasterIdLst>
                <p:sldIdLst>
                    <p:sldId id="256" r:id="rId2"/>
                </p:sldIdLst>
                <p:sldSz cx="9144000" cy="6858000"/>
                <p:notesSz cx="6858000" cy="9144000"/>
            </p:presentation>""";

    private static final String PPTX_SLIDE_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
            </Relationships>""";

    private static final String PPTX_SLIDE = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <p:cSld>
                    <p:spTree>
                        <p:nvGrpSpPr>
                            <p:cNvPr id="1" name=""/>
                            <p:cNvGrpSpPr/>
                            <p:nvPr/>
                        </p:nvGrpSpPr>
                        <p:grpSpPr/>
                    </p:spTree>
                </p:cSld>
            </p:sld>""";

    private static final String PPTX_SLIDE_LAYOUT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
            </Relationships>""";

    private static final String PPTX_SLIDE_LAYOUT = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" type="blank">
                <p:cSld>
                    <p:spTree>
                        <p:nvGrpSpPr>
                            <p:cNvPr id="1" name=""/>
                            <p:cNvGrpSpPr/>
                            <p:nvPr/>
                        </p:nvGrpSpPr>
                        <p:grpSpPr/>
                    </p:spTree>
                </p:cSld>
            </p:sldLayout>""";

    private static final String PPTX_SLIDE_MASTER_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
                <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
            </Relationships>""";

    private static final String PPTX_SLIDE_MASTER = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <p:cSld>
                    <p:spTree>
                        <p:nvGrpSpPr>
                            <p:cNvPr id="1" name=""/>
                            <p:cNvGrpSpPr/>
                            <p:nvPr/>
                        </p:nvGrpSpPr>
                        <p:grpSpPr/>
                    </p:spTree>
                </p:cSld>
                <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
                <p:sldLayoutIdLst>
                    <p:sldLayoutId id="2147483649" r:id="rId1"/>
                </p:sldLayoutIdLst>
            </p:sldMaster>""";

    private static final String PPTX_THEME = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">
                <a:themeElements>
                    <a:clrScheme name="Office">
                        <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>
                        <a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
                        <a:dk2><a:srgbClr val="44546A"/></a:dk2>
                        <a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
                        <a:accent1><a:srgbClr val="4472C4"/></a:accent1>
                        <a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
                        <a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
                        <a:accent4><a:srgbClr val="FFC000"/></a:accent4>
                        <a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>
                        <a:accent6><a:srgbClr val="70AD47"/></a:accent6>
                        <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
                        <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
                    </a:clrScheme>
                    <a:fontScheme name="Office">
                        <a:majorFont>
                            <a:latin typeface="Calibri Light"/>
                            <a:ea typeface=""/>
                            <a:cs typeface=""/>
                        </a:majorFont>
                        <a:minorFont>
                            <a:latin typeface="Calibri"/>
                            <a:ea typeface=""/>
                            <a:cs typeface=""/>
                        </a:minorFont>
                    </a:fontScheme>
                    <a:fmtScheme name="Office">
                        <a:fillStyleLst>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                        </a:fillStyleLst>
                        <a:lnStyleLst>
                            <a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>
                            <a:ln w="12700"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>
                            <a:ln w="19050"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>
                        </a:lnStyleLst>
                        <a:effectStyleLst>
                            <a:effectStyle><a:effectLst/></a:effectStyle>
                            <a:effectStyle><a:effectLst/></a:effectStyle>
                            <a:effectStyle><a:effectLst/></a:effectStyle>
                        </a:effectStyleLst>
                        <a:bgFillStyleLst>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                            <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
                        </a:bgFillStyleLst>
                    </a:fmtScheme>
                </a:themeElements>
            </a:theme>""";
}
