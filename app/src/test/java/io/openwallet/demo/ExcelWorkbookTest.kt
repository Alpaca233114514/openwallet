package io.openwallet.demo

import io.openwallet.demo.domain.*
import io.openwallet.demo.export.ExcelWorkbook
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExcelWorkbookTest {
    private val account = Account(1, "银行卡 & 现金", 0, 0)
    private val entry = Entry(12, 1, 1000, 2850, Direction.EXPENSE, "=HYPERLINK(\"test\") <咖啡> 😀\u0001", "餐饮", externalId = "00001234567890123456789")
    private fun workbook(state: WalletState): Map<String, ByteArray> {
        val output = ByteArrayOutputStream()
        ExcelWorkbook.write(state, output)
        return buildMap {
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) { val item = zip.nextEntry ?: break; put(item.name, zip.readBytes()) }
            }
        }
    }
    private fun parse(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    @Test fun realXlsxPackagePreservesTextNumbersAndPostedSnapshot() {
        val state = WalletState(listOf(account), listOf(entry.copy(id = 13, time = 2000), entry.copy(id = 99, pending = true), entry))
        val parts = workbook(state)
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml"), parts.keys)
        parts.values.forEach { parse(it) }
        val sheet = parse(parts.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(3, sheet.getElementsByTagName("row").length)
        assertEquals(0, sheet.getElementsByTagName("f").length)
        val cells = sheet.getElementsByTagName("c")
        val byRef = (0 until cells.length).associate { val c = cells.item(it) as org.w3c.dom.Element; c.getAttribute("r") to c }
        assertEquals("28.50", byRef.getValue("D2").textContent)
        assertFalse(byRef.getValue("D2").hasAttribute("t"))
        assertEquals("00001234567890123456789", byRef.getValue("G2").textContent)
        assertEquals("inlineStr", byRef.getValue("G2").getAttribute("t"))
        assertEquals(entry.merchant.replace('\u0001', '\uFFFD'), byRef.getValue("E2").textContent)
        assertEquals("12", byRef.getValue("I2").textContent)
        assertEquals("13", byRef.getValue("I3").textContent)
        assertEquals("A1:I3", (sheet.getElementsByTagName("autoFilter").item(0) as org.w3c.dom.Element).getAttribute("ref"))
        File("build/excel-validation").mkdirs()
        File("build/excel-validation/sample.xlsx").outputStream().use { ExcelWorkbook.write(state, it) }
    }
    @Test fun emptyLedgerHasAUsableHeaderAndNoStaleTransactions() {
        val sheet = parse(workbook(WalletState()).getValue("xl/worksheets/sheet1.xml"))
        assertEquals(1, sheet.getElementsByTagName("row").length)
        assertEquals(9, sheet.getElementsByTagName("c").length)
        assertTrue(sheet.documentElement.textContent.contains("金额（元）"))
    }
    @Test fun oversizedCellFailsBeforeWritingAnyOutput() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            ExcelWorkbook.write(WalletState(listOf(account), listOf(entry.copy(merchant = "x".repeat(32768)))), output)
        }
        assertEquals(0, output.size())
    }
}
