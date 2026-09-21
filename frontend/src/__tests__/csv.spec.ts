import { describe, expect, it } from 'vitest';
import { csvCell } from '@/utils/csv';

describe('csvCell (#430)', () => {
  it('quotes every cell and doubles embedded quotes', () => {
    expect(csvCell('plain')).toBe('"plain"');
    expect(csvCell('has "quote"')).toBe('"has ""quote"""');
    expect(csvCell('has, comma')).toBe('"has, comma"');
    expect(csvCell(null)).toBe('""');
    expect(csvCell(12)).toBe('"12"');
  });

  it('prefixes formula-leading cells so spreadsheets render text', () => {
    expect(csvCell('=SUM(A1)')).toBe('"\'=SUM(A1)"');
    expect(csvCell('+1')).toBe('"\'+1"');
    expect(csvCell('-danger')).toBe('"\'-danger"');
    expect(csvCell('@cmd')).toBe('"\'@cmd"');
    expect(csvCell('\tcmd')).toBe('"\'\tcmd"');
  });

  it('leaves formula characters inside the cell untouched', () => {
    expect(csvCell('a=b')).toBe('"a=b"');
    expect(csvCell('1+1')).toBe('"1+1"');
  });
});
