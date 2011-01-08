/*
 * This file is part of the SMT project.
 * Copyright 2010 David R. Cok
 * Created August 2010
 */
package org.smtlib.impl;

import java.io.*;

import org.smtlib.CharSequenceInfinite;
import org.smtlib.CharSequenceReader;
import org.smtlib.IPos;
import org.smtlib.SMT;

/** Represents a range of characters in a source - used to indicate the location of errors */
public class Pos implements IPos {

	/** The starting character, beginning from 0 */
	int charStart;
	/** One past the last character of the range, beginning from 0 */
	int charEnd;
	
	/** The source of text */
	/*@Nullable*/ISource source;
	
	/** Constructor for a Pos object
	 * @param cs the start position of the character range, 0-based
	 * @param ce one past the end position of the character range, 0- based
	 * @param s the source of text to which the character positions refer
	 */
	public Pos(int cs, int ce, /*@Nullable*/ISource s) {
		this.charStart = cs;
		this.charEnd = ce;
		this.source = s;
	}
	
	/** The starting character, beginning from 0 */
	public int charStart() { return charStart; }
	/** One past the last character of the range, beginning from 0 */
	public int charEnd() { return charEnd; }
	/** The source of text */
	public /*@Nullable*/ISource source() { return source; }

	/** An implementation of IPosable, holding an instance of Pos,
	 *  that can be used as a base class if necessary */
	public static class Posable implements IPosable {
		protected /*@Nullable*/ IPos pos;
		public IPos pos() { return pos; }
		public void setPos(IPos pos) { this.pos = pos; }
	}
	
	/** An implementation of the ISource interface */
	public static class Source implements ISource {
		private Reader rdr = null;
		
		/** Creates a Source from a character sequence
		 * @param cs the sequence to use as a source of characters
		 * @param location a designator of the location of the source, used for identification only
		 */
		public Source(CharSequence cs, /*@Nullable*/ Object location) {
			this.chars = cs;
			this.location = location;
		}

		/** Creates a Source from a File
		 * @param smtConfig the SMT Configuration object
		 * @param f the File object from which to read characters
		 * @throws java.io.FileNotFoundException if a problem occurred opening or reading the file
		 */
		public Source(SMT.Configuration smtConfig, java.io.File f) throws java.io.FileNotFoundException {
			rdr = new FileReader(f);
			CharSequenceReader csr = new CharSequenceReader(rdr,100000,0,2);
			csr.prompter = new SMT.Prompter(smtConfig);
			chars = csr;
			this.location = f.getPath();
		}
		
		/** Creates a Source from a File
		 * @param smtConfig the SMT Configuration object
		 * @param f the InputStream object from which to read characters
		 * @param location object describing the location of the source
		 */
		public Source(SMT.Configuration smtConfig, InputStream f, Object location) {
			rdr = new InputStreamReader(f);
			CharSequenceReader csr = new CharSequenceReader(rdr,100000,0,2);
			csr.prompter = new SMT.Prompter(smtConfig);
			chars = csr;
			this.location = location;
		}
		
		public void close() {
			try {
				rdr.close();
			} catch (IOException e) {}
		}
		
		/** The sequence of characters */
		private CharSequence chars;
		/** The sequence of characters */
		public CharSequence chars() { return chars; }
		
		/** The identifier for the location */
		private /*@Nullable*/ Object location;
		/** The identifier for the location */
		public /*@Nullable*/ Object location() { return location; }

		@Override
		public char charAt(int pos) {
			return this.chars.charAt(pos);
		}
		
		@Override
		public int lineBeginning(int pos) {
			int p = pos;
			if (p > 0 && charAt(p) == '\n' && charAt(p-1) == '\r') --p;
			char c;
			while (p >= 0 && (c=charAt(p)) != '\n' && c != '\r') --p;
			return p+1;
		}
		
		private final static String eol = System.getProperty("line.separator");
		
		@Override
		public String textLine(int pos) {
			int b = lineBeginning(pos);
			int e = nextLineTermination(pos);
			String s = chars.subSequence(b,e+1).toString();
			char c = s.length() == 0 ? ' ' : s.charAt(s.length()-1);
			if (c != '\n' && c != '\r') s = s + eol;
			return s;
		}

		/** The (last character of) the next line termination sequence after the given character position */
		//@ requires pos >= 0;
		//@ ensures \result >= pos;
		protected int nextLineTermination(int pos) {
			char c;
			while ((c=charAt(pos)) != '\n' && c != '\r' && c != CharSequenceInfinite.endChar) ++pos;
			if (c == '\r' && charAt(pos+1) == '\n') ++pos;
			else if (c == CharSequenceInfinite.endChar) --pos;
			return pos;
		}
		
		@Override
		public int lineNumber(int pos) {
			int line = 1;
			char c;
			for (int i=0; i<pos; i++) {
				c = charAt(i);
				if (c == '\n') line++;
				else if (c == '\r') {
					line++;
					if (charAt(i+1) == '\n') i++;
				}
			}
			return line;
		}
	}
}
