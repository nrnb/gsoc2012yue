package org.cytoscape.app.internal.net;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.ImageIcon;

import org.apache.commons.io.IOUtils;
import org.cytoscape.app.internal.exception.AppDownloadException;
import org.cytoscape.app.internal.manager.AppManager;
import org.cytoscape.app.internal.net.WebQuerierBackup.AppTag;
import org.cytoscape.app.internal.util.DebugHelper;
import org.cytoscape.io.util.StreamUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for querying the Cytoscape App Store web service to obtain
 * information about available apps and app updates.
 */
public class WebQuerierBackup {
	
	private static final String DEFAULT_APP_STORE_URL = "http://apps3.nrnb.org/";
	
	private static final String REQUEST_JSON_HEADER_KEY = "X-Requested-With";
	private static final String REQUEST_JSON_HEADER_VALUE = "XMLHttpRequest";
	
	/**
	 * A regular expression for version lists that are compatible with the current version of Cytoscape.
	 */
	private static final String COMPATIBLE_RELEASE_REGEX = "(^\\s*|.*,)\\s*3(\\..*)?\\s*(\\s*$|,.*)";
	
	private static final Logger logger = LoggerFactory.getLogger(WebQuerierBackup.class);
	
	private StreamUtil streamUtil;
	
	/** A reference to the result obtained by the last successful query for all available apps. */
	private Set<WebApp> apps;
	
	/** A reference to a map which keeps track of the known set of apps for each known tag */
	private Map<String, Set<WebApp>> appsByTagName;
	
	/** A reference to the result obtained by the last successful query for all available app tags. */
	private Map<String, AppTag> appTags;
	
	/**
	 * A reference to the result obtained by the last successful query for all available apps 
	 * to this app store URL.
	 */
	private Map<String, Set<WebApp>> appsByUrl;
	
	/** 
	 * A reference to a map of maps keeping track, for each app store URL, the known set of 
	 * apps for each known tag from that URL.
	 */
	private Map<String, Map<String, Set<WebApp>>> appsByTagNameByUrl;
	
	/**
	 * A reference to the set of all known tags for a given app store URL. The tags are stored
	 * in a map that maps the tag's string name to a tag object containing more information
	 * about the tag, such as the number of apps associated with it.
	 */
	private Map<String, Map<String, AppTag>> appTagsByUrl;
	
	
	private String currentAppStoreUrl = DEFAULT_APP_STORE_URL;
	
	/**
	 * A class that represents a tag used for apps, containing information about the tag
	 * such as its unique name used on the app store website as well as its human-readable name.
	 */
	public class AppTag {
		
		/** A unique name of the tag used by the app store website as a tag identifier */
		private String name;
		
		/** The name of the tag that is shown to the user */
		private String fullName;
		
		/** The number of apps associated with this tag */
		private int count;
		
		public AppTag() {
		}
		
		/** Obtain the name of the tag, which is a unique name used by the app store website as an identifier */
		public String getName() {
			return name;
		}
		
		/** Obtain the name of the tag that is shown to the user */
		public String getFullName() {
			return fullName;
		}
		
		/** Obtain the number of apps known by the web store to be associated with this tag */
		public int getCount() {
			return count;
		}
		
		public void setName(String name) {
			this.name = name;
		}
		
		public void setFullName(String fullName) {
			this.fullName = fullName;
		}
		
		public void setCount(int count) {
			this.count = count;
		}
		
		@Override
		public String toString() {
			return fullName + " (" + count + ")";
		}
	}
	
	public WebQuerierBackup(StreamUtil streamUtil) {
		this.streamUtil = streamUtil;
		
		apps = null;
		appTags = new HashMap<String, AppTag>();
		appsByTagName = new HashMap<String, Set<WebApp>>();
		
		/*
		Set<WebApp> webApps = getAllApps();
		
		DebugHelper.print("Apps found: " + webApps.size());
		*/
	}
	
	/**
	 * Makes a HTTP query using the given URL and returns the response as a string.
	 * @param url The URL used to make the HTTP request
	 * @return The response, as a string
	 * @throws IOException If there was an error while attempting to make a connection
	 * to the given URL
	 */
	private String query(String url) throws IOException {
		// Convert the string url to a URL object
		URL parsedUrl = null;
		try {
			parsedUrl = new URL(url);
		} catch (MalformedURLException e) {
			throw new IOException("Malformed url, " + e.getMessage());
		}
		
		String result = null;
	
		HttpURLConnection connection = (HttpURLConnection) streamUtil.getURLConnection(parsedUrl);
		connection.setRequestProperty(REQUEST_JSON_HEADER_KEY, REQUEST_JSON_HEADER_VALUE);
		connection.connect();
		
		InputStream inputStream = connection.getInputStream();
		result = IOUtils.toString(inputStream, "UTF-8");
		
		connection.disconnect();
		
		return result;
	}
	
	public String getDefaultAppStoreUrl() {
		return DEFAULT_APP_STORE_URL;
	}
	
	public String getCurrentAppStoreUrl(String url) {
		return currentAppStoreUrl;
	}
	
	/**
	 * Sets the current base url used for app store queries. If the url is malformed,
	 * no change is made.
	 * 
	 * @param url The base url of the app store, e.g. http://apps.cytoscape.org/
	 */
	public void setCurrentAppStoreUrl(String url) {
		boolean malformed = false;
		
		try {
			URL checkMalformed = new URL(url);
		} catch (MalformedURLException e) {
			malformed = true;
			logger.warn("Malformed URL: " + url + ", " + e.getMessage());
		}
		
		if (!malformed) {
			if (!url.trim().endsWith("/")) {
				currentAppStoreUrl = url + "/";
			} else {
				currentAppStoreUrl = url;
			}
		}
	}
	
	/**
	 * Return the set of all tag names found on the app store. 
	 * @return The set of all available tag names
	 */
	public Set<AppTag> getAllTags() {
		// Make a query for all apps if not done so; tag information for each app is returned
		// by the web store and is used to build a set of all available tags
		Set<WebApp> apps = getAllApps();
		
		return new HashSet<AppTag>(appTags.values());
	}
	
	public Set<WebApp> getAllApps() {
		// If we have a cached result from the previous query, use that one
		if (apps != null) {
			return apps;
		}
		
		DebugHelper.print("Obtaining apps from app store..");

		Set<WebApp> result = new HashSet<WebApp>();
		
		String jsonResult = null;
		try {
			// Obtain information about the app from the website
			jsonResult = query(DEFAULT_APP_STORE_URL + "backend/all_apps");
			
			// Parse the JSON result
			JSONArray jsonArray = new JSONArray(jsonResult);
			JSONObject jsonObject = null;
			String keyName;
			
			for (int index = 0; index < jsonArray.length(); index++) {
				jsonObject = jsonArray.getJSONObject(index);
				
				WebApp webApp = new WebApp();
				
				keyName = "fullname";
				if (jsonObject.has(keyName)) {
					webApp.setName(jsonObject.get(keyName).toString());
					webApp.setFullName(jsonObject.get(keyName).toString());
				} else {
					continue;
				}
				
				keyName = "icon_url";
				if (jsonObject.has(keyName)) {
					webApp.setIconUrl(jsonObject.get(keyName).toString());
				}
				
				keyName = "page_url";
				if (jsonObject.has(keyName)) {
					webApp.setPageUrl(currentAppStoreUrl.substring(0, currentAppStoreUrl.length() - 1) 
							+ jsonObject.get(keyName).toString());
				}
				
				keyName = "description";
				if (jsonObject.has(keyName)) {
					webApp.setDescription(jsonObject.get(keyName).toString());
				}
				
				keyName = "downloads";
				if (jsonObject.has(keyName)) {
					try {
						webApp.setDownloadCount(Integer.parseInt(jsonObject.get(keyName).toString()));
					} catch (NumberFormatException e) {
					}
				}
				
				keyName = "stars_percentage";
				if (jsonObject.has(keyName)) {
					try {
						webApp.setStarsPercentage(Integer.parseInt(jsonObject.get(keyName).toString()));
					} catch (NumberFormatException e) {
					}
				}
				
				keyName = "votes";
				if (jsonObject.has(keyName)) {
					try {
						webApp.setVotes(Integer.parseInt(jsonObject.get(keyName).toString()));
					} catch (NumberFormatException e) {
					}
				}
				
				try {
					List<WebApp.Release> releases = new LinkedList<WebApp.Release>();
					
					if (jsonObject.has("releases")) {
						JSONArray jsonReleases = jsonObject.getJSONArray("releases");
						JSONObject jsonRelease;
						
						for (int releaseIndex = 0; releaseIndex < jsonReleases.length(); releaseIndex++) {
							jsonRelease = jsonReleases.getJSONObject(releaseIndex);
							
							WebApp.Release release = new WebApp.Release();
							
							release.setRelativeUrl(jsonRelease.get("release_download_url").toString());
							release.setReleaseDate(jsonRelease.get("created_iso").toString());
							release.setReleaseVersion(jsonRelease.get("version").toString());
							
							keyName = "works_with";
							if (jsonRelease.has(keyName)) {
								release.setCompatibleCytoscapeVersions(jsonRelease.get(keyName).toString());
							}
							
							releases.add(release);
						}
						
						// Sort releases by release date
						Collections.sort(releases);
					}
					
					webApp.setReleases(releases);
				} catch (JSONException e) {
					logger.warn("Error obtaining releases for app: " + webApp.getFullName() + ", " 
							+ e.getMessage());
				}
				
				// DebugHelper.print("Obtaining ImageIcon: " + iconUrlPrefix + webApp.getIconUrl());
				// webApp.setImageIcon(new ImageIcon(new URL(iconUrlPrefix + webApp.getIconUrl())));

				
				// Check the app for compatible releases
				List<WebApp.Release> compatibleReleases = getCompatibleReleases(webApp);
				
				// Only add this app if it has compatible releases
				if (compatibleReleases.size() > 0) {
					// Obtain tags associated with this app
					processAppTags(webApp, jsonObject);
	
					result.add(webApp);
				}
			}
			
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			DebugHelper.print("Error parsing JSON: " + e.getMessage());
			e.printStackTrace();
		}
	
		
		DebugHelper.print(result.size() + " apps found from web store.");
		
		// Cache the result of this query
		this.apps = result;
		return result;
	}
	
	private void processAppTags(WebApp webApp, JSONObject jsonObject) throws JSONException {
		// Obtain tags associated with this app from the JSONObject representing the app data in JSON format obtained
		// from the web store
		
		JSONArray appTagObjects = jsonObject.optJSONArray("tags");
		if (appTagObjects == null) {
			return;
		}
		
		for (int index = 0; index < appTagObjects.length(); index++) {
			/*
			JSONObject appTagObject = appTagObjects.getJSONObject(index);
			
			String appTagName = appTagObject.get("fullname").toString();
			*/
			
			String appTagName = appTagObjects.get(index).toString();
			
			AppTag appTag = appTags.get(appTagName);
			
			if (appTag == null) {
				appTag = new AppTag();
				appTag.setName(appTagName);
				// appTag.setFullName(appTagObject.get("fullname").toString());
				appTag.setFullName(appTagName);
			
				appTag.setCount(0);
				appTags.put(appTagName, appTag);
			}
			
			
			webApp.getAppTags().add(appTag);
			
			// Add the app information for this tag to the map which keeps apps categorized by tag
			if (appsByTagName.get(appTagName) == null) {
				appsByTagName.put(appTagName, new HashSet<WebApp>());
			}
			
			appsByTagName.get(appTagName).add(webApp);
			appTag.setCount(appTag.getCount() + 1);
		}
	}
	
	/**
	 * Given the unique app name used by the app store, query the app store for the 
	 * download URL and download the app to the given directory.
	 * 
	 * If a file with the same name exists in the directory, it is overwritten.
	 * 
	 * @param appName The unique app name used by the app store
	 * @param version The desired version, or <code>null</code> to obtain the latest release
	 * @param directory The directory used to store the downloaded file
	 */
	public File downloadApp(WebApp webApp, String version, File directory) throws AppDownloadException {
	
		List<WebApp.Release> compatibleReleases = getCompatibleReleases(webApp);
		
		if (compatibleReleases.size() > 0) {
			WebApp.Release releaseToDownload = null;
			
			if (version != null) {
				for (WebApp.Release compatibleRelease : compatibleReleases) {
					
					// Check if the desired version is found in the list of available versions
					if (compatibleRelease.getReleaseVersion().matches(
							"(^\\s*|.*,)\\s*" + version + "\\s*(\\s*$|,.*)")) {
						releaseToDownload = compatibleRelease;
					}
				}
				
				if (releaseToDownload == null) {
					throw new AppDownloadException("No release with the requested version " + version
							+ " was found for the requested app " + webApp.getFullName());
				}
			} else {
				releaseToDownload = compatibleReleases.get(compatibleReleases.size() - 1);
			}
			
			URL downloadUrl = null;
			try {
				downloadUrl = new URL(DEFAULT_APP_STORE_URL + releaseToDownload.getRelativeUrl());
			} catch (MalformedURLException e) {
				throw new AppDownloadException("Unable to obtain URL for version " + version 
						+ " of the release for " + webApp.getFullName());
			}
			
			if (downloadUrl != null) {
				try {
				
					// Prepare to download
					ReadableByteChannel readableByteChannel = Channels.newChannel(downloadUrl.openStream());
					
					// Output file has same name as app, but spaces are replaced with hyphens
					File outputFile = new File(directory.getCanonicalPath() + File.separator 
							+ webApp.getName().replaceAll("\\s", "-") + ".jar");
					
					if (outputFile.exists()) {
						outputFile.delete();
					}
					
					outputFile.createNewFile();
					
				    FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
				    fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, 1 << 24);
				    
				    return outputFile;
				} catch (IOException e) {
					throw new AppDownloadException("Error while downloading app " + webApp.getFullName()
							+ ": " + e.getMessage());
				}
			}
		} else {
			throw new AppDownloadException("No available releases were found for the app " 
					+ webApp.getFullName() + ".");
		}
		
		return null;
	}

	/**
	 * Returns the list of compatible releases, in chronological order starting with the earliest.
	 * @param webApp
	 * @return
	 */
	private List<WebApp.Release> getCompatibleReleases(WebApp webApp) {
		List<WebApp.Release> compatibleReleases = new LinkedList<WebApp.Release>();
		
		for (WebApp.Release release : webApp.getReleases()) {
			
			// Get releases that are compatible with the current version of Cytoscape (version 3)
			if (release.getCompatibleCytoscapeVersions().matches(COMPATIBLE_RELEASE_REGEX)) {
				compatibleReleases.add(release);
			}
		}
		
		return compatibleReleases;
	}
	
	
	public Set<WebApp> getAppsByTag(String tagName) {
		// Query for apps (which includes tag information) if not done so
		Set<WebApp> webApps = getAllApps();
		
		return appsByTagName.get(tagName);
	}
}
