package io.openwallet.demo.export

import io.openwallet.demo.domain.WalletState
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small, streaming SpreadsheetML writer. Text is always literal, never an Excel formula. */
object ExcelWorkbook {
    const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val FILE_NAME = "OpenWallet-流水.xlsx"
    fun write(state: WalletState, output: OutputStream): Int {
        val rows = SheetsRows.from(state)
        require(rows.size <= 1_048_576) { "流水超过 Excel 单工作表行数限制" }
        require(rows.all { row -> row.all { it.length <= 32_767 } }) { "流水文本超过 Excel 单元格长度限制" }
        ZipOutputStream(output).use { zip ->
            fun part(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
            part("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            part("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="已入账流水" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            part("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            part("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF246B59"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="3"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/><xf numFmtId="4" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            fun xml(value: String) { zip.write(value.toByteArray(Charsets.UTF_8)) }
            xml("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:I${rows.size}"/><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="23" customWidth="1"/><col min="2" max="3" width="16" customWidth="1"/><col min="4" max="4" width="16" customWidth="1"/><col min="5" max="5" width="30" customWidth="1"/><col min="6" max="9" width="22" customWidth="1"/></cols><sheetData>""")
            rows.forEachIndexed { index, row ->
                xml("<row r=\"${index + 1}\">")
                row.forEachIndexed { column, value ->
                    val ref = "${'A' + column}${index + 1}"
                    if (index > 0 && column == 3) xml("<c r=\"$ref\" s=\"2\"><v>$value</v></c>")
                    else xml("<c r=\"$ref\" t=\"inlineStr\" s=\"${if (index == 0) 1 else 0}\"><is><t xml:space=\"preserve\">${escape(value)}</t></is></c>")
                }
                xml("</row>")
            }
            xml("</sheetData><autoFilter ref=\"A1:I${rows.size}\"/></worksheet>")
            zip.closeEntry()
        }
        return rows.size - 1
    }

    private fun escape(text: String): String = buildString {
        text.codePoints().forEach { point ->
            when (point) {
                38 -> append("&amp;"); 60 -> append("&lt;"); 62 -> append("&gt;"); 13 -> append("&#13;")
                else -> if (point == 9 || point == 10 || point in 32..0xD7FF || point in 0xE000..0xFFFD || point in 0x10000..0x10FFFF)
                    appendCodePoint(point) else append('\uFFFD')
            }
        }
    }
}
