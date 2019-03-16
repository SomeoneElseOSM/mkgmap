/*
 * Copyright (C) 2018.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 3 or
 * version 2 as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 */
package uk.me.parabola.imgfmt.sys;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import uk.me.parabola.imgfmt.FileExistsException;
import uk.me.parabola.imgfmt.FileSystemParam;
import uk.me.parabola.imgfmt.fs.DirectoryEntry;
import uk.me.parabola.imgfmt.fs.FileSystem;
import uk.me.parabola.imgfmt.fs.ImgChannel;

public class GmapFS implements FileSystem {
	private final String filename;

	private GmapFS(String name) {
		this.filename = name;
	}

	public ImgChannel create(String name) throws FileExistsException {
		return null;
	}

	public ImgChannel open(String name, String mode) throws FileNotFoundException {
		return null;
	}

	public DirectoryEntry lookup(String name) throws IOException {
		return null;
	}

	public List<DirectoryEntry> list() {
		ArrayList<DirectoryEntry> entries = new ArrayList<>();
		DirectoryEntry ent = new GmapDirent();
		return entries;
	}

	public FileSystemParam fsparam() {
		return null;
	}

	public void sync() throws IOException {

	}

	public void close() {
		try {
			sync();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static FileSystem openFs(String name) {
		GmapFS fs = new GmapFS(name);
		fs.init();
		return fs;
	}

	private void init() {
		Path infoPath = Paths.get(filename, "Info.xml");
		File f = new File(filename, "Info.xml");
		XMLInputFactory factory = XMLInputFactory.newInstance();

		//List<Product> products = new ArrayList<>();
		try (InputStream is = new FileInputStream(f)) {

			XMLStreamReader r = factory.createXMLStreamReader(is);

			Product currProduct = new Product(filename);
			for (int type = r.next(); r.hasNext(); type = r.next()) {
				if (type == XMLStreamConstants.START_ELEMENT) {
					System.out.println("got type " + type + ", for " + r.getLocalName());
					switch (r.getLocalName()) {
					case "IDX":
						System.out.println("got IDX " + r.getElementText());
						break;
					case "SubProduct":
						currProduct = new Product(filename);
						//products.add(currProduct);
						break;
					case "BaseMap":
						currProduct.basemap = r.getElementText();
						break;
					case "Directory":
						currProduct.dirname = r.getElementText();

						break;
					case "TDB":
						currProduct.tdb = r.getElementText();
						break;
					default:
						System.out.println("ele  from " + r.getLocalName());
					}
				} else if (type == XMLStreamConstants.END_ELEMENT) {
					if (Objects.equals(r.getLocalName(), "SubProduct")) {
						currProduct.process();
					}
				}
			}

	} catch (NoSuchElementException | IOException | XMLStreamException e) {
			e.printStackTrace();
		}
	}


	public static void main(String... args) throws FileNotFoundException {
		FileSystem fs = FileSystem.openFS(args[0]);

		List<DirectoryEntry> list = fs.list();
		for (DirectoryEntry dir : list) {
			System.out.println(dir.getFullName());
		}
	}

	class Product {
		private final String basedir;
		private String dirname;
		private String basemap;
		private String tdb;

		public Product(String basedir) {
			this.basedir = basedir;
		}

		void process() throws IOException {
			if (dirname == null)
				return;

			//Files.walk(new File(filename, dirname).toPath()).forEach(System.out::println);
			//Files.walkFileTree(Paths.get(filename, dirname), new SimpleFileVisitor<Path>() {
			//	public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
			//		return FileVisitResult.CONTINUE;
			//	}
			//
			//	public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
			//		System.out.println(file + ", size=" + attrs.size());
			//		return super.visitFile(file, attrs);
			//	}
			//});

			dirname = null;
		}
	}
}

class GmapDirent implements DirectoryEntry {
	private String name;
	private String ext;
	private int size;

	public String getName() {
		return name;
	}

	public String getExt() {
		return ext;
	}

	public String getFullName() {
		return name + "." + ext;
	}

	public int getSize() {
		return size;
	}

	public boolean isSpecial() {
		return false;
	}
}
