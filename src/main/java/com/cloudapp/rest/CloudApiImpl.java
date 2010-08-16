package com.cloudapp.rest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CloudApiImpl implements CloudApi {

  private static final String HOST = "http://cl.ly/";
  private static final Logger LOGGER = LoggerFactory.getLogger(CloudApiImpl.class);

  private DefaultHttpClient client;

  public CloudApiImpl(String mail, String pw) {
    client = new DefaultHttpClient();
    client.setReuseStrategy(new DefaultConnectionReuseStrategy());

    // Try to authenticate.
    AuthScope scope = new AuthScope("my.cl.ly", 80);
    client.getCredentialsProvider().setCredentials(scope,
        new UsernamePasswordCredentials(mail, pw));
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see com.cloudapp.rest.CloudApi#createBookmark(java.lang.String, java.lang.String)
   */
  public JSONObject createBookmark(String name, String url) throws CloudApiException {
    try {
      // Apparently we have to post a JSONObject ..
      JSONObject item = new JSONObject();
      item.put("name", name);
      item.put("redirect_url", url);
      JSONObject bodyObj = new JSONObject();
      bodyObj.put("item", item);
      String body = bodyObj.toString();

      HttpPost post = new HttpPost("http://my.cl.ly/items/new");
      MultipartEntity entity = new MultipartEntity();
      post.abort();

    } catch (JSONException e) {
      LOGGER.error("Error when trying to convert the return output to JSON.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    }

    return null;
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see com.cloudapp.rest.CloudApi#uploadFile(java.io.File)
   */
  @SuppressWarnings("rawtypes")
  public JSONObject uploadFile(File file) throws CloudApiException {
    HttpGet keyRequest = null;
    HttpPost uploadRequest = null;
    try {
      // Get a key for the file first.
      keyRequest = new HttpGet("http://my.cl.ly/items/new");
      keyRequest.addHeader("Accept", "application/json");

      // Execute the request.
      HttpResponse response = client.execute(keyRequest);
      int status = response.getStatusLine().getStatusCode();
      if (status == 200) {
        String body = EntityUtils.toString(response.getEntity());
        JSONObject json = new JSONObject(body);
        String url = json.getString("url");
        JSONObject params = json.getJSONObject("params");
        // From the API docs
        // Use this response to construct the upload. Each item in params becomes a
        // separate parameter you'll need to post to url. Send the file as the parameter
        // file.
        MultipartEntity entity = new MultipartEntity(HttpMultipartMode.BROWSER_COMPATIBLE);
        // Add all the plain parameters.
        Iterator keys = params.keys();
        while (keys.hasNext()) {
          String key = (String) keys.next();
          entity.addPart(key, new StringBody(params.getString(key)));
        }

        // Add the actual file.
        // We have to use the 'file' parameter for the S3 storage.
        FileBody fileBody = new FileBody(file);
        entity.addPart("file", fileBody);

        uploadRequest = new HttpPost(url);
        uploadRequest.addHeader("Accept", "application/json");
        uploadRequest.setEntity(entity);

        // Perform the actual upload.
        // uploadMethod.setFollowRedirects(true);
        response = client.execute(uploadRequest);
        status = response.getStatusLine().getStatusCode();
        if (status == 200) {
          body = EntityUtils.toString(response.getEntity());
          return new JSONObject(body);
        }
        throw new CloudApiException(500, "Was unable to upload the file to amazon.", null);

      }
      throw new CloudApiException(500,
          "Was unable to retrieve a key from CloudApp to upload a file.", null);

    } catch (IOException e) {
      LOGGER.error("Error when trying to upload a file.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    } catch (JSONException e) {
      LOGGER.error("Error when trying to convert the return output to JSON.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    } finally {
      if (keyRequest != null) {
        keyRequest.abort();
      }
      if (uploadRequest != null) {
        uploadRequest.abort();
      }

    }
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see com.cloudapp.rest.CloudApi#getItems()
   */
  public JSONArray getItems(int page, int itemsPerPage, String type, boolean showDeleted)
      throws CloudApiException {
    HttpGet request = null;
    try {
      // Sanitize the parameters
      if (page < 1) {
        page = 1;
      }
      if (itemsPerPage < 1) {
        itemsPerPage = 1;
      }

      // Prepare the list of parameters.
      List<NameValuePair> nvps = new ArrayList<NameValuePair>();
      nvps.add(new BasicNameValuePair("page", "" + page));
      nvps.add(new BasicNameValuePair("per_page", "" + itemsPerPage));
      if (type != null) {
        nvps.add(new BasicNameValuePair("type", type));
      }
      nvps.add(new BasicNameValuePair("deleted", (showDeleted) ? "true" : "false"));

      // Prepare the URI (the host and querystring.)
      URI uri = URIUtils.createURI("http", "my.cl.ly", -1, "/items",
          URLEncodedUtils.format(nvps, "UTF-8"), null);

      // Prepare the request.
      request = new HttpGet(uri);
      request.addHeader("Accept", "application/json");

      // Perform the request.
      HttpResponse response = client.execute(request);
      response.addHeader("Accept", "application/json");
      int status = response.getStatusLine().getStatusCode();
      String body = EntityUtils.toString(response.getEntity(), "UTF-8");

      // We always need 200 for items retrieval.
      if (status == 200) {
        return new JSONArray(body);
      }

      // Anything else is a failure.
      throw new CloudApiException(status, body, null);

    } catch (IOException e) {
      LOGGER.error("Error when trying to retrieve the items.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    } catch (JSONException e) {
      LOGGER.error("Error when trying to parse the response as JSON.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    } catch (URISyntaxException e) {
      LOGGER.error("Error when trying to retrieve the items.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    } finally {
      if (request != null) {
        request.abort();
      }
    }
  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see com.cloudapp.rest.CloudApi#getItem(java.lang.String)
   */
  public JSONObject getItem(String id) throws CloudApiException {
    // No need to be authenticated to retrieve this item.
    HttpGet request = new HttpGet(HOST + id);
    request.addHeader("Accept", "application/json");

    try {
      // Perform the actual GET.
      HttpResponse response = client.execute(request);
      int status = response.getStatusLine().getStatusCode();
      String body = EntityUtils.toString(response.getEntity(), "UTF-8");

      // We're really only interested in 200 responses.
      if (status == 200) {
        return new JSONObject(body);
      }

      // If the status is not 200, that means there is a failure.
      throw new CloudApiException(status, body, null);

    } catch (IOException e) {
      LOGGER.error("Error when trying to retrieve an item.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    } catch (JSONException e) {
      LOGGER.error("Error when trying to parse the response as JSON.", e);
      throw new CloudApiException(500, e.getMessage(), e);
    } finally {
      if (request != null) {
        request.abort();
      }
    }

  }

  /**
   * 
   * {@inheritDoc}
   * 
   * @see com.cloudapp.rest.CloudApi#deleteItem(java.lang.String)
   */
  public void deleteItem(String href) throws CloudApiException {
    HttpDelete request = null;
    try {
      // To delete an item we just have to a a DELETE request to http//my.cl.ly/id.
      request = new HttpDelete(href);
      HttpResponse response = client.execute(request);
      int status = response.getStatusLine().getStatusCode();

      // If it isn't a 302 it failed.
      if (status != 302) {
        String body = EntityUtils.toString(response.getEntity());
        throw new CloudApiException(status, body, null);
      }
    } catch (IOException e) {
      LOGGER.error("Error when trying to delete an item.", e);
      throw new CloudApiException(500, e.getMessage(), null);
    } finally {
      if (request != null) {
        request.abort();
      }
    }

  }

}