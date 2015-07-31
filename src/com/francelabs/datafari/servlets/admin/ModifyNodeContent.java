package com.francelabs.datafari.servlets.admin;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.francelabs.datafari.solrj.SolrServers.Core;

/**
 * This Servlet is used to print and modify the textContent of various nodes of the solrConfig.xml
 * It is called by SizeLimitations.html and by AutocompleteConfiguration.html
 * DoGet is used to get the value of the requested node cleans and creates the semaphores
 * DoPost is used to modify the value of the requested node
 * There is one semaphore by node requested since the start/restart of Datafari
 * @author Alexis Karassev
 */
@WebServlet("/admin/ModifyNodeContent")
public class ModifyNodeContent extends HttpServlet {
	private static final long serialVersionUID = 1L;
	private String server = Core.FILESHARE.toString();
	private static final List<SemaphoreLn> listMutex = new ArrayList<SemaphoreLn>();
	private String env;
	private Document doc;
	private File config = null;
	private final static Logger LOGGER = Logger.getLogger(ModifyNodeContent.class
			.getName());
	/**
	 * @see HttpServlet#HttpServlet()
	 * Gets the path
	 * Checks if the required file exist
	 */
	public ModifyNodeContent() {
		env = System.getenv("DATAFARI_HOME");									//Gets the directory of installation if in standard environment
		if(env==null){															//If in development environment	
			RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();	//Gets the D.solr.solr.home variable given in arguments to the VM
			List<String> arguments = runtimeMxBean.getInputArguments();
			for(String s : arguments){
				if(s.startsWith("-Dsolr.solr.home"))
					env = s.substring(s.indexOf("=")+1, s.indexOf("solr_home")-5);
			}
		}
		if(new File(env+"/solr/solr_home/"+server+"/conf/solrconfig.xml").exists())
			config = new File(env+"/solr/solr_home/"+server+"/conf/solrconfig.xml");
	}
	/**
	 * @see HttpServlet#doGet(HttpServletRequest request, HttpServletResponse response)
	 * Used to release the semaphore 
	 * Or create and or acquire the semaphore, then read the file to get the requested node
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try{
			String type = request.getParameter("type");
			try {
				if(request.getParameter("sem") != null){									//If it's called just to clean the semaphore
					for ( int i = 0 ; i < listMutex.size() ; i++){
						if(listMutex.get(i).getType().equals(type) && listMutex.get(i).availablePermits()<1){
							listMutex.get(i).release();
							return;
						}
					}
					return;
				}
				if( config == null || !new File(env+"/solr/solr_home/"+server+"/conf/solrconfig.xml").exists()){//If the files did not existed when the constructor was runned
					//Checks if they exist now
					if(!new File(env+"/solr/solr_home/"+server+"/conf/solrconfig.xml").exists()){
						LOGGER.error("Error while opening the configuration file, solrconfig.xml, in ModifyNodeContent doGet, please make sure this file exists at "+env+"/solr/solr_home/"+server+"/conf/ . Error 69033");		//If not an error is printed
						PrintWriter out = response.getWriter();
						out.append("Error while opening the configuration file, please retry, if the problem persists contact your system administrator. Error Code : 69033"); 	
						out.close();
						return;
					}else
						config = new File(env+"/solr/solr_home/"+server+"/conf/solrconfig.xml");
				}
				boolean mutex = false;
				for (int i = 0 ; i < listMutex.size() ; i++) {
					if (listMutex.get(i).getType().equals(type)){
						if(listMutex.get(i).availablePermits()>0){
							listMutex.get(i).acquire();
						}else{
							PrintWriter out = response.getWriter();
							out.append("File already in use"); 	
							out.close();
							return;
						}
						mutex = true;
					}
				}
				if(!mutex){
					listMutex.add(new SemaphoreLn("", type));
					listMutex.get(listMutex.size()-1).acquire();
				}
				DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
				DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
				doc = dBuilder.parse(config);
				NodeList childNodes = doc.getChildNodes();
				Element elem = (Element) run(childNodes, type);
				PrintWriter out = response.getWriter();
				out.append(elem.getTextContent()); 	
				out.close();
			} catch ( ParserConfigurationException | SAXException e) {
				LOGGER.error("Error while parsing the solrconfig.xml, in ModifyNodeContent doGet, make sure the file is valid. Error 69034", e);
				PrintWriter out = response.getWriter();
				out.append("Something bad happened, please retry, if the problem persists contact your system administrator. Error code : 69034"); 	
				out.close();
				return;
			}
		}catch(Exception e){
			PrintWriter out = response.getWriter();
			out.append("Something bad happened, please retry, if the problem persists contact your system administrator. Error code : 69514");
			out.close();
			LOGGER.error("Unindentified error in ModifyNodeContent doGet. Error 69514", e);
		}
	}

	/**
	 * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse response)
	 * Read the file and search for the requested node, then set it's textContent to the parameter
	 */
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		try{
			String type = request.getParameter("type");
			String value = request.getParameter("value");
			Element elem;
			try {
				DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
				DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
				doc = dBuilder.parse(config);
				NodeList childNodes = doc.getChildNodes();
				elem = (Element) run(childNodes, type);
			}catch (ParserConfigurationException | SAXException e) {
				LOGGER.error("Error while parsing the solrconfig.xml, in ModifyNodeContent doPost, make sure the file is valid. Error 69035", e);
				PrintWriter out = response.getWriter();
				out.append("Something bad happened, please retry, if the problem persists contact your system administrator. Error code : 69035"); 	
				out.close();
				return;
			}
			try {
				elem.setTextContent(value);
				TransformerFactory transformerFactory = TransformerFactory.newInstance();
				Transformer transformer;
				transformer = transformerFactory.newTransformer();
				DOMSource source = new DOMSource(doc);
				StreamResult result = new StreamResult(config);
				transformer.transform(source, result);
				for(int i = 0 ; i < listMutex.size() ; i++){
					if(listMutex.get(i).getType().equals(type) && listMutex.get(i).availablePermits()<1)
						listMutex.get(i).release();
				}
			} catch (TransformerException e) {
				LOGGER.error("Error while modifying the solrconfig.xml, in ModifyNodeContent doPost. Error 69036", e);
				PrintWriter out = response.getWriter();
				out.append("Something bad happened, please retry, if the problem persists contact your system administrator. Error code : 69036"); 	
				out.close();
				return;
			}
		}catch(Exception e){
			PrintWriter out = response.getWriter();
			out.append("Something bad happened, please retry, if the problem persists contact your system administrator. Error code : 69515");
			out.close();
			LOGGER.error("Unindentified error in ModifyNodeContent doPost. Error 69515", e);
		}


	}

	private Node run(NodeList child, String type){
		for(int i = 0 ; i<child.getLength(); i ++){
			String name = "";
			if(child.item(i).hasAttributes()){
				NamedNodeMap map = child.item(i).getAttributes();
				for(int j = 0 ; j < map.getLength() ; j++){
					if (map.item(j).getNodeName().equals("name"))
						name = map.item(j).getNodeValue();
				}
				if(name.equals(type))
					return child.item(i);
			}
			if(child.item(i).hasChildNodes())
				if(run(child.item(i).getChildNodes(), type)!=null)
					return run(child.item(i).getChildNodes(), type);
		}
		return null;
	}

}
