/**
 * RFC 4180 cell quoting plus the spreadsheet formula-injection guard (#430):
 * a cell starting with =, +, -, @, TAB or CR is executed as a formula when the
 * CSV is opened in Excel/LibreOffice — prefix it with an apostrophe (the
 * displayed text is unchanged, execution is neutralized).
 */
export function csvCell(value: unknown): string {
  let text = value == null ? '' : String(value);
  if (/^[=+\-@\t\r]/.test(text)) {
    text = `'${text}`;
  }
  return `"${text.replace(/"/g, '""')}"`;
}
