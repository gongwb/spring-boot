/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.loader.jar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilePermission;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.net.URLStreamHandler;
import java.security.Permission;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link java.net.JarURLConnection} used to support {@link JarFile#getUrl()}.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 */
class JarURLConnection extends java.net.JarURLConnection {

	private static final FileNotFoundException FILE_NOT_FOUND_EXCEPTION = new FileNotFoundException();

	private static final String SEPARATOR = "!/";

	private static final URL EMPTY_JAR_URL;

	static {
		try {
			EMPTY_JAR_URL = new URL("jar:", null, 0, "file:!/", new URLStreamHandler() {
				@Override
				protected URLConnection openConnection(URL u) throws IOException {
					// Stub URLStreamHandler to prevent the wrong JAR Handler from being
					// Instantiated and cached.
					return null;
				}
			});
		}
		catch (MalformedURLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static final JarEntryName EMPTY_JAR_ENTRY_NAME = new JarEntryName("");

	private static final String READ_ACTION = "read";

	private static final Map<String, String> absoluteFileCache = Collections
			.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {

				@Override
				protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
					return size() >= 50;
				}

			});

	private static ThreadLocal<Boolean> useFastExceptions = new ThreadLocal<Boolean>();

	private final JarFile jarFile;

	private final Permission permission;

	private URL jarFileUrl;

	private final JarEntryName jarEntryName;

	private JarEntry jarEntry;

	protected JarURLConnection(URL url, JarFile jarFile) throws IOException {
		// What we pass to super is ultimately ignored
		super(EMPTY_JAR_URL);
		this.url = url;
		String spec = getNormalizedFileUrl(url)
				.substring(jarFile.getUrl().getFile().length());
		int separator;
		while ((separator = spec.indexOf(SEPARATOR)) > 0) {
			jarFile = getNestedJarFile(jarFile, spec.substring(0, separator));
			spec = spec.substring(separator + SEPARATOR.length());
		}
		this.jarFile = jarFile;
		this.jarEntryName = getJarEntryName(spec);
		this.permission = new FilePermission(jarFile.getRootJarFile().getFile().getPath(),
				READ_ACTION);
	}

	private String getNormalizedFileUrl(URL url) {
		String file = url.getFile();
		String path = "";
		int separatorIndex = file.indexOf(SEPARATOR);
		if (separatorIndex > 0) {
			path = file.substring(separatorIndex);
			file = file.substring(0, separatorIndex);
		}
		String absoluteFile = JarURLConnection.absoluteFileCache.get(file);
		if (absoluteFile == null) {
			absoluteFile = new File(URI.create(file).getSchemeSpecificPart())
					.getAbsoluteFile().toURI().toString();
			JarURLConnection.absoluteFileCache.put(file, absoluteFile);
		}
		return absoluteFile + path;
	}

	private JarFile getNestedJarFile(JarFile jarFile, String name) throws IOException {
		JarEntry jarEntry = jarFile.getJarEntry(name);
		if (jarEntry == null) {
			throwFileNotFound(jarEntry, jarFile);
		}
		return jarFile.getNestedJarFile(jarEntry);
	}

	private JarEntryName getJarEntryName(String spec) {
		if (spec.length() == 0) {
			return EMPTY_JAR_ENTRY_NAME;
		}
		return new JarEntryName(spec);
	}

	@Override
	public void connect() throws IOException {
		if (!this.jarEntryName.isEmpty() && this.jarEntry == null) {
			this.jarEntry = this.jarFile.getJarEntry(getEntryName());
			if (this.jarEntry == null) {
				throwFileNotFound(this.jarEntryName, this.jarFile);
			}
		}
		this.connected = true;
	}

	private void throwFileNotFound(Object entry, JarFile jarFile)
			throws FileNotFoundException {
		if (Boolean.TRUE.equals(useFastExceptions.get())) {
			throw FILE_NOT_FOUND_EXCEPTION;
		}
		throw new FileNotFoundException(
				"JAR entry " + entry + " not found in " + jarFile.getName());
	}

	@Override
	public JarFile getJarFile() throws IOException {
		connect();
		return this.jarFile;
	}

	@Override
	public URL getJarFileURL() {
		if (this.jarFileUrl == null) {
			this.jarFileUrl = buildJarFileUrl();
		}
		return this.jarFileUrl;
	}

	private URL buildJarFileUrl() {
		try {
			String spec = this.jarFile.getUrl().getFile();
			if (spec.endsWith(SEPARATOR)) {
				spec = spec.substring(0, spec.length() - SEPARATOR.length());
			}
			if (spec.indexOf(SEPARATOR) == -1) {
				return new URL(spec);
			}
			return new URL("jar:" + spec);
		}
		catch (MalformedURLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public JarEntry getJarEntry() throws IOException {
		if (this.jarEntryName.isEmpty()) {
			return null;
		}
		connect();
		return this.jarEntry;
	}

	@Override
	public String getEntryName() {
		return this.jarEntryName.toString();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		if (this.jarEntryName.isEmpty()) {
			throw new IOException("no entry name specified");
		}
		connect();
		InputStream inputStream = this.jarFile.getInputStream(this.jarEntry);
		if (inputStream == null) {
			throwFileNotFound(this.jarEntryName, this.jarFile);
		}
		return inputStream;
	}

	@Override
	public int getContentLength() {
		try {
			if (this.jarEntryName.isEmpty()) {
				return this.jarFile.size();
			}
			JarEntry entry = getJarEntry();
			return (entry == null ? -1 : (int) entry.getSize());
		}
		catch (IOException ex) {
			return -1;
		}
	}

	@Override
	public Object getContent() throws IOException {
		connect();
		return (this.jarEntryName.isEmpty() ? this.jarFile : super.getContent());
	}

	@Override
	public String getContentType() {
		return this.jarEntryName.getContentType();
	}

	@Override
	public Permission getPermission() throws IOException {
		return this.permission;
	}

	static void setUseFastExceptions(boolean useFastExceptions) {
		JarURLConnection.useFastExceptions.set(useFastExceptions);
	}

	/**
	 * A JarEntryName parsed from a URL String.
	 */
	static class JarEntryName {

		private final String name;

		private String contentType;

		JarEntryName(String spec) {
			this.name = decode(spec);
		}

		private String decode(String source) {
			int length = (source == null ? 0 : source.length());
			if ((length == 0) || (source.indexOf('%') < 0)) {
				return new AsciiBytes(source).toString();
			}
			ByteArrayOutputStream bos = new ByteArrayOutputStream(length);
			write(source, bos);
			// AsciiBytes is what is used to store the JarEntries so make it symmetric
			return new AsciiBytes(bos.toByteArray()).toString();
		}

		private void write(String source, ByteArrayOutputStream outputStream) {
			int length = source.length();
			for (int i = 0; i < length; i++) {
				int c = source.charAt(i);
				if (c > 127) {
					try {
						String encoded = URLEncoder.encode(String.valueOf((char) c),
								"UTF-8");
						write(encoded, outputStream);
					}
					catch (UnsupportedEncodingException ex) {
						throw new IllegalStateException(ex);
					}
				}
				else {
					if (c == '%') {
						if ((i + 2) >= length) {
							throw new IllegalArgumentException(
									"Invalid encoded sequence \"" + source.substring(i)
											+ "\"");
						}
						c = decodeEscapeSequence(source, i);
						i += 2;
					}
					outputStream.write(c);
				}
			}
		}

		private char decodeEscapeSequence(String source, int i) {
			int hi = Character.digit(source.charAt(i + 1), 16);
			int lo = Character.digit(source.charAt(i + 2), 16);
			if (hi == -1 || lo == -1) {
				throw new IllegalArgumentException(
						"Invalid encoded sequence \"" + source.substring(i) + "\"");
			}
			return ((char) ((hi << 4) + lo));
		}

		@Override
		public String toString() {
			return this.name;
		}

		public boolean isEmpty() {
			return this.name.length() == 0;
		}

		public String getContentType() {
			if (this.contentType == null) {
				this.contentType = deduceContentType();
			}
			return this.contentType;
		}

		private String deduceContentType() {
			// Guess the content type, don't bother with streams as mark is not supported
			String type = (isEmpty() ? "x-java/jar" : null);
			type = (type != null ? type : guessContentTypeFromName(toString()));
			type = (type != null ? type : "content/unknown");
			return type;
		}

	}

}
