/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.plugins;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.IOUtil;
import org.w3c.dom.Document;

public class RegisterExtension {
	
	final Log log;
	
	public RegisterExtension(Log log) {
		this.log = log;
	}

	/**
	 * registers extension to standalone.xml
	 * @param srcFile source file (standalone.xml or domain.xml)
	 * @param destFile output file (standalone.xml or domain.xml with new stuff)
	 * @param profiles array of profiles (only applies if srcFile is domain.xml)
	 * @param subsystem subsystem file (may not need exist)
	 * @param moduleId ID of JBoss Module to register as an JBoss extension
	 * @throws Exception
	 */
	public void register(File srcFile, File destFile, String[] profiles, File subsystem,
			String moduleId) throws Exception {
		try {
			log.info("Register extension module="+moduleId);
			DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
			DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
			
			// we still need to read standalone.xml and subsystem.xml to DOM to be able to detect namespaces
			Document srcDoc = dBuilder.parse(srcFile);
			Document subsystemDoc = null;

			// unset profiles in case we're not editing domain.xml
			if (isStandaloneXml(srcDoc)) {
				profiles = null;
			}
			
			if (subsystem.isFile() && subsystem.canRead()) {
				subsystemDoc = dBuilder.parse(subsystem);
				log.info("Configuring subsystem from file "+subsystem);
			} else {
				log.info("Subsystem file ["+subsystem+"] does not exist, subsystem will not be configured");
			}

			// do XSL transformation
			TransformerFactory f = TransformerFactory.newInstance();
			Transformer t = f.newTransformer(new StreamSource(createStylesheet(
					getNameSpace(srcDoc), moduleId, getNameSpace(subsystemDoc),
					doc2string(subsystemDoc),profiles)));
			Source s = new StreamSource(srcFile);
			Result r = new StreamResult(destFile);
			t.setURIResolver(null);
			t.setOutputProperty(OutputKeys.INDENT, "yes");
			t.setOutputProperty(OutputKeys.STANDALONE, "yes");
			t.setOutputProperty(OutputKeys.METHOD, "xml");
			t.transform(s, r);
			log.info("New serverConfig file written to ["+destFile.getAbsolutePath()+"]");

		} catch (TransformerConfigurationException e) {
			throw new Exception(e.toString());
		} catch (TransformerException e) {
			throw new Exception(e.toString());
		}
	}

	private InputStream createStylesheet(String namespace, String module,
			String subns, String subsystem, String[] profileNames) throws IOException {
		// when writing new nodes we'll ignore wring xmlns attribute
		String ignoreNs = "s"; // s is main namespace for server config
		boolean isSubsystem = subns != null && subsystem != null;

		String profileSelector = createXPathProfileNameSelector(profileNames);

		StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" ?>");
		sheet.append("<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\""
				+ " xmlns:s=\"" + namespace + "\"");
		
		if (isSubsystem) {
			sheet.append(" xmlns:sub=\"" + subns + "\"");
			ignoreNs += " sub"; // sub is namespace for subsystem we're setting up
		}
		sheet.append(" exclude-result-prefixes=\"" + ignoreNs
				+ "\" version=\"1.0\">");

		// for both extensions and profiles we first copy all nodes by excluding potentially existing one and then we append new content

		// append new extension
		sheet.append("<xsl:variable name=\"extension\"><extension module=\""+ module+ "\" /></xsl:variable>");
		sheet.append("<xsl:template match=\"s:extensions\"><xsl:copy><xsl:apply-templates select=\"*[not(@module='"
				+ module
				+ "')]|node()\" /></xsl:copy>"
				+ "</xsl:template><xsl:template match=\"s:extension[last()]\"><xsl:copy-of select=\"$extension\" /></xsl:template>");

		// append new subsystem
		if (isSubsystem) {
			sheet.append("<xsl:variable name=\"subsystem\">"+ subsystem+ "</xsl:variable>");
			sheet.append("<xsl:template match=\"@s:profile"+profileSelector+"\"><xsl:copy><xsl:apply-templates select=\"*[not(namespace-uri() ='"
					+ subns
					+ "')]|node()\" /></xsl:copy>"
					+ "</xsl:template><xsl:template match=\"s:profile"+profileSelector+"/*[last()]\"><xsl:copy-of select=\"$subsystem\" /></xsl:template>");
		}

		// generic identity template
		sheet.append("<xsl:template match=\"@*|node()\"><xsl:copy><xsl:apply-templates select=\"@*|node()\" /></xsl:copy></xsl:template>");
		sheet.append("</xsl:stylesheet>");
		log.debug("XSL :"+sheet);
		return new ByteArrayInputStream(IOUtil.toByteArray(sheet.toString()));
	}

	private static String createXPathProfileNameSelector(String[] profiles) {
		if (profiles == null || profiles.length == 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder("[");
		for (String profile : profiles) {
			sb.append("@name='"+profile+"' or ");
		}
		sb.delete(sb.length()-4, sb.length());
		sb.append("]");
		return sb.toString();
	}

	private static boolean isStandaloneXml(Document doc) {
		return "server".equals(doc.getDocumentElement().getNodeName());
	}

	/**
	 * return XML namespace for root of given document
	 * @param doc
	 * @return
	 */
	private static String getNameSpace(Document doc) {
		if (doc == null) {
			return null;
		}
		return doc.getDocumentElement().getAttribute("xmlns");
	}

	/**
	 * return content of XML document as string
	 * @param doc
	 * @return
	 */
	private static String doc2string(Document doc) {
		if (doc == null) {
			return null;
		}
		try {
			StringWriter sw = new StringWriter();
			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer transformer = tf.newTransformer();
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
					"yes");
			transformer.setOutputProperty(OutputKeys.METHOD, "xml");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
			transformer.transform(new DOMSource(doc), new StreamResult(sw));
			return sw.toString();
		} catch (Exception ex) {
			throw new RuntimeException("Error converting to String", ex);
		}
	}
}
