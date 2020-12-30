package com.tririga.custom;

import com.sun.istack.internal.ByteArrayDataSource;
import com.tririga.app.PropertiesLookup;
import com.tririga.platform.smartobject.domain.SmartObject;
import com.tririga.platform.smartobject.domain.SmartObjectField;
import com.tririga.platform.smartobject.domain.field.BinaryField;
import com.tririga.platform.smartobject.domain.field.NumberField;
import com.tririga.platform.smartobject.domain.field.SmartObjectFieldValueAccessor;
import com.tririga.platform.smartobject.domain.field.TextField;
import com.tririga.platform.smartobject.service.SmartObjectUtils;
import com.tririga.platform.smartobject.service.bean.BinaryFieldValue;
import com.tririga.platform.util.classloader.ClassLoaders;
import com.tririga.platform.util.classloader.CustomClassLoader;
import com.tririga.platform.util.classloader.application.dao.dto.CustomClassLoaderInfo;
import com.tririga.pub.adapter.IConnect;
import com.tririga.web.util.PropertyReader;
import com.tririga.ws.TririgaWS;
import com.tririga.ws.dto.*;
import com.tririga.ws.dto.content.Content;
import com.tririga.ws.dto.content.Response;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.apache.commons.io.IOUtils;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

public class ResourceFileUpdate implements IConnect {

    private static String resourceName = "";
    private static final Logger logger = Logger.getLogger(ResourceFileUpdate.class);

    private boolean enableDebug = true;
    private JSONArray debugDetails = new JSONArray();

    @Override
    public void execute(TririgaWS tririgaWS, HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        String ERROR = "";
        OutputStream outputStream = response.getOutputStream();
        JSONObject output = new JSONObject();
        long resourceFileRecordID = -1;

        doDebug("Execution Started");
        String fullUrl = request.getPathInfo();
        doDebug("Full URL : " + fullUrl);
        String urlUptoIConnect = fullUrl.substring(getClass().getSimpleName().length() + 1);
        doDebug("urlUptoIconnect : " + urlUptoIConnect);
        label_001:
        {

            if (urlUptoIConnect.length() < 2) {
                ERROR = "Invalid URL !! after " + getClass().getSimpleName() + ", There should be at least 2 Character. Did you missed to mention ResourceFile Name ??";
                break label_001;
            }

            resourceName = urlUptoIConnect.substring(1);
            doDebug("Resource Name : " + resourceName);

            resourceFileRecordID = runNamedQuery(tririgaWS, resourceName);
            if (resourceFileRecordID == -1) {
                ERROR = "Unable to locate Resource File with name : " + resourceName;
                break label_001;
            }

            ServletInputStream inputStream = request.getInputStream();
            byte[] bytes = IOUtils.toByteArray(inputStream);

            if (bytes.length == 0) {
                ERROR = "Uploaded JAR length cant be 0 ";
                break label_001;
            }

            PropertyReader propertyReader = new PropertyReader(PropertiesLookup.WEB_PROPERTIES, "MAXIMUM_UPLOAD_FILE_SIZE_MEGABYTES");
            long max_upload_size_MB = Long.parseLong(propertyReader.getValue());
            doDebug("Max Upload File size in MB : " + max_upload_size_MB);
            long max_upload_size = max_upload_size_MB * 1024 * 1024;
            doDebug("Maximum Upload size for the environment is detected as : " + max_upload_size);

            if (bytes.length > max_upload_size) {
                ERROR = "JAR cant be Uploaded, MAX Upload Size defined for system is (MB) : " + max_upload_size_MB;
                break label_001;
            }

            final DataSource fds = new ByteArrayDataSource(bytes, "application/java-archive");
            final DataHandler uploadFile = new DataHandler(fds);

            Content content = new Content();
            content.setRecordId(resourceFileRecordID);
            content.setContent(uploadFile);
            content.setFieldName("ResourceFileBI");
            content.setFileName(resourceName + ".jar");

            try {


                Response upload = tririgaWS.upload(content);
                if (!upload.getStatus().equals("Success")) {
                    throw new Exception("Upload Response of Trirga is Failed");
                }
                
            } catch (Exception e) {
                logger.error("Unable to Upload content : " + e.getMessage());
                e.printStackTrace();
                doDebug("Upload Exception : " + e.getMessage());
                ERROR = "Error happened while uploading data into Resource File : " + resourceName;
                break label_001;
            }

            try {

                ResponseHelperHeader triSave = tririgaWS.triggerActions(
                        new TriggerActions[]{
                                new TriggerActions(resourceFileRecordID,
                                        "triSave")
                        }
                );
                if (triSave.isAnyFailed()) {
                    ERROR = "Failed to Save the Resource File, with value = " + triSave.getResponseHelpers()[0].getValue();
                    break label_001;
                } else {
                    doDebug("Successfully Triggered Save on Resource File!!");
                    doDebug("Resource Found SpecID : " + resourceFileRecordID);
                }
            } catch (Exception e) {
                ERROR = "Exception happened while trying to Trigger Save on Resource File.";
                doDebug("Exception on Triggering Save : "+e.getMessage());
                break label_001;
            }
        }

        if (ERROR.length() == 0) {

            try {
                Association[] uses = tririgaWS.getAssociatedRecords(resourceFileRecordID, "Uses", Integer.MAX_VALUE / 4);
                doDebug("Total Classloader using the Resource are : "+uses.length);
                for (Association association : uses) {
                    long recordId = association.getAssociatedRecordId();
                    doDebug("Match Found with Record id : "+recordId);
                    SmartObject smartObject = SmartObjectUtils.getSmartObject(recordId);
                    NumberField numberField = (NumberField) smartObject.getField("RevisionNU");
                    String oldRevisionNo = SmartObjectFieldValueAccessor.getString(numberField);
                    doDebug("Old Revision No : "+oldRevisionNo);
                    SmartObjectFieldValueAccessor.setString(numberField, String.valueOf(Integer.parseInt(oldRevisionNo)+1));
                }
                doDebug("Finished");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        doDebug("Execution Finished !!");
        if (ERROR.length() == 0) {
            output.put("status", "success");
            response.setStatus(HttpServletResponse.SC_OK);
        } else {
            output.put("error", ERROR);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        }
        response.setContentType("application/json");

        if (enableDebug) {
            doDebug("End!!");
            output.put("Debug", debugDetails);
        }

        outputStream.write(output.toString().getBytes());
        outputStream.flush();
    }

    private static long runNamedQuery(TririgaWS tririgaWS, String resourceName) {

        Filter[] f = new Filter[1];
        f[0] = new Filter();
        f[0].setDataType(320);
        f[0].setFieldName("NameTX");
        f[0].setSectionName("General");
        f[0].setOperator(10);
        f[0].setValue(resourceName);


        try {

            QueryResult queryResult = tririgaWS.runNamedQuery("", "System", "ResourceFile", "ResourceFile - Display - Used By - ClassLoader", f, 1, Integer.MAX_VALUE / 4);

            QueryResponseHelper[] queryResponseHelpers = queryResult.getQueryResponseHelpers();

            long resultLength = queryResult.getTotalResults();

            if (resultLength == 0) {
                logger.error("Unable to find Resource with name : " + resourceName);
                return -1;
            } else if (resultLength > 1) {
                logger.error(resultLength + " Resources found with name : " + resourceName);
                return -1;
            } else {
                return Long.parseLong(queryResponseHelpers[0].getRecordId());
            }

        } catch (Exception e) {
            logger.error("Unable to Run Named Query : " + e.getMessage());
            return -1L;
        }

    }

    private void doDebug(String s) {
        if (enableDebug)
            debugDetails.put(s);
    }
}
