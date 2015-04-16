package org.allseen.alljoyn;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.os.Looper;
import android.os.Message;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.Timer;
import java.util.TimerTask;
import java.util.HashMap;


public class AllJoynCordova extends CordovaPlugin
{
    /* Load the native alljoyn library. */
    static
    {
        System.loadLibrary("alljoyn");
    }

    private static final String TAG = "AllJoynCordova";
    private static final short  CONTACT_PORT=42;
    private static final String DAEMON_AUTH = "ALLJOYN_PIN_KEYX";
    private static final String DAEMON_PWD = "1234"; // 000000 or 1234

    private static final long AJ_MESSAGE_SLOW_LOOP_INTERVAL = 500;
    private static final long AJ_MESSAGE_FAST_LOOP_INTERVAL = 50;
    private static final long UNMARSHAL_TIMEOUT = 1000 * 5;
    private static final long CONNECT_TIMEOUT = 1000 * 60;
    private static final long METHOD_TIMEOUT = 100 * 10;

    private static final long AJ_SIGNAL_FOUND_ADV_NAME = (((alljoynConstants.AJ_BUS_ID_FLAG) << 24) | (((1)) << 16) | (((0)) << 8) | (1));   /**< signal for found advertising name */
    private static final long AJ_RED_ID_FLAG = 0x80;
    private static final long AJ_METHOD_JOIN_SESSION = ((long)(((long)alljoynConstants.AJ_BUS_ID_FLAG) << 24) | (((long)(1)) << 16) | (((long)(0)) << 8) | (10));

    private AJ_BusAttachment bus;
    private AJ_Object proxyObjects;
    private AJ_Object appObjects;
    private Timer m_pTimer = null;
    private boolean m_bStartTimer = false;
    private HashMap m_pMessageHandlers = new HashMap<String, String>();

    /**
     * Sets the context of the Command. This can then be used to do things like
     * get file paths associated with the Activity.
     *
     * @param cordova The context of the main Activity.
     * @param webView The CordovaWebView Cordova is running in.
     */
    @Override
    public void initialize(final CordovaInterface cordova, CordovaWebView webView)
    {
        super.initialize(cordova, webView);
        Log.i(TAG, "Initialization running.");
        alljoyn.AJ_Initialize();
        bus = new AJ_BusAttachment();
        proxyObjects = new AJ_Object();
        appObjects = new AJ_Object();

        // Initialize timer for msg loop
        m_pTimer = new Timer();
        m_pTimer.scheduleAtFixedRate
        (
                new TimerTask()
                {
                    @Override
                    public void run()
                    {
                        if (!m_bStartTimer)
                        {
                            return;
                        }

                        _AJ_Message msg = new _AJ_Message();
                        AJ_Status status = alljoyn.AJ_UnmarshalMsg(bus, msg, UNMARSHAL_TIMEOUT);

                        if (status == AJ_Status.AJ_OK)
                        {
                            final long msgId = msg.getMsgId();

                            if (m_pMessageHandlers.containsKey(msgId))
                            {
                                MsgHandler handler = (MsgHandler)m_pMessageHandlers.get(msgId);

                                try
                                {
                                    handler.callback(msg);
                                }
                                catch (Exception e)
                                {
                                    Log.i(TAG, e.toString());
                                }
                            }
                            else
                            {
                                /*
                                 * Pass to the built-in bus message handlers
                                 */
                                Log.i(TAG, "AJ_BusHandleBusMessage() msgId=" + msgId);
                                status = alljoyn.AJ_BusHandleBusMessage(msg);
                            }
                        }
                        else if(status == AJ_Status.AJ_ERR_TIMEOUT)
                        {
                            // Nothing to do for now, continue i guess
                            Log.i(TAG, "Timeout getting MSG. Will try again...");
                            status = AJ_Status.AJ_OK;
                        }
                        else if (status == AJ_Status.AJ_ERR_NO_MATCH)
                        {
                            // Ignore unknown messages
                            Log.i(TAG, "AJ_ERR_NO_MATCH in main loop. Ignoring!");
                            status = AJ_Status.AJ_OK;
                        }
                        else
                        {
                            Log.i(TAG, " -- MainLoopError AJ_UnmarshalMsg returned status=" + alljoyn.AJ_StatusText(status));
                        }

                        alljoyn.AJ_CloseMsg(msg);
                    }
                },
                AJ_MESSAGE_SLOW_LOOP_INTERVAL,
                AJ_MESSAGE_SLOW_LOOP_INTERVAL
        );

        Log.i(TAG, "Initialization completed.");
    }

    /**
     * Executes the request and returns PluginResult.
     *
     * @param action            The action to execute.
     * @param args              JSONArray of arguments for the plugin.
     * @param callbackContext   The callback context used when calling back into JavaScript.
     * @return                  True when the action was valid, false otherwise.
     */
    @Override
    public boolean execute(String action, JSONArray data, CallbackContext callbackContext) throws JSONException
    {
        if (action.equals("connect"))
        {
            String serviceName = data.getString(0);

            if (serviceName.length() == 0)
            {
                serviceName = null;
            }

            long timeout = data.getLong(1);
            AJ_Status status = null;
            Log.i(TAG, "AllJoyn.connect("+bus+","+serviceName+","+timeout+")");

            try
            {
                status = alljoyn.AJ_FindBusAndConnect(bus, serviceName, timeout);
            }
            catch (Exception e)
            {
                Log.i(TAG, "Exception finding and connecting to bus: " + e.toString());
            }

            Log.i(TAG, "Called AJ_FindBusAndConnect, status = " + status);

            if (status == AJ_Status.AJ_OK)
            {
                callbackContext.success("Connected to router!");
                return true;
            }
            else
            {
                callbackContext.error("Error connecting to router: " + status.toString());
                return false;
            }
        }
        else if (action.equals("registerObjects"))
        {
            AJ_Status status = null;
            AJ_Object local = null;
            JSONArray localObjects = null;
            JSONArray remoteObjects = null;
            AJ_Object remote = null;

            Log.i(TAG, "AllJoyn.registerObjects()");

            if (data.isNull(0))
            {
                Log.i(TAG, "AllJoyn.registerObjects: arg 0 null");
            }
            else
            {
                localObjects = data.getJSONArray(0);
                local = alljoyn.AJ_ObjectsCreate();

                for (int i = 0; i < localObjects.length() - 1; i++)
                {
                    JSONObject object = localObjects.getJSONObject(i);
                    AJ_Object nObj = new AJ_Object();

                    // Init path
                    nObj.setPath(object.getString("path"));

                    // Init interfaces
                    JSONArray interfacesDesc = object.getJSONArray("interfaces");
                    SWIGTYPE_p_p_p_char interfaces = alljoyn.AJ_InterfacesCreate();
                    for (int j = 0; j < interfacesDesc.length(); j++)
                    {
                        if (!interfacesDesc.isNull(j))
                        {
                            JSONArray interfaceDesc = interfacesDesc.getJSONArray(j);
                            SWIGTYPE_p_p_char ifaceMethods = null;

                            for (int k = 0; k < interfaceDesc.length(); k++)
                            {
                                if (ifaceMethods == null)
                                {
                                    ifaceMethods = alljoyn.AJ_InterfaceDescriptionCreate(interfaceDesc.getString(k));
                                }
                                else
                                {
                                    if (interfaceDesc.getString(k).length() > 0)
                                    {
                                        ifaceMethods = alljoyn.AJ_InterfaceDescriptionAdd(ifaceMethods, interfaceDesc.getString(k));
                                    }
                                }
                            }

                            interfaces = alljoyn.AJ_InterfacesAdd(interfaces, ifaceMethods);
                        }
                    }
                    nObj.setInterfaces(interfaces);

                    local = alljoyn.AJ_ObjectsAdd(local, nObj);
                }

                Log.i(TAG, "AllJoyn.registerObjects() Local: " + localObjects.toString() + " => " + local.toString());
            }

            if (data.isNull(1))
            {
                Log.i(TAG, "AllJoyn.registerObjects: arg 1 null");
            }
            else
            {
                remoteObjects = data.getJSONArray(1);
                remote = alljoyn.AJ_ObjectsCreate();

                for (int i = 0; i < remoteObjects.length() - 1; i++)
                {
                    JSONObject object = remoteObjects.getJSONObject(i);
                    AJ_Object nObj = new AJ_Object();

                    // Init path
                    nObj.setPath(object.getString("path"));

                    // Init interfaces
                    JSONArray interfacesDesc = object.getJSONArray("interfaces");
                    SWIGTYPE_p_p_p_char interfaces = alljoyn.AJ_InterfacesCreate();
                    for (int j = 0; j < interfacesDesc.length(); j++)
                    {
                        if (!interfacesDesc.isNull(j))
                        {
                            JSONArray interfaceDesc = interfacesDesc.getJSONArray(j);
                            SWIGTYPE_p_p_char ifaceMethods = null;
                            for (int k = 0; k < interfaceDesc.length(); k++)
                            {
                                if (ifaceMethods == null)
                                {
                                    ifaceMethods = alljoyn.AJ_InterfaceDescriptionCreate(interfaceDesc.getString(k));
                                }
                                else
                                {
                                    if (interfaceDesc.getString(k).length() > 0)
                                    {
                                        ifaceMethods = alljoyn.AJ_InterfaceDescriptionAdd(ifaceMethods, interfaceDesc.getString(k));
                                    }
                                }
                            }
                            interfaces = alljoyn.AJ_InterfacesAdd(interfaces, ifaceMethods);
                        }
                    }
                    nObj.setInterfaces(interfaces);

                    remote = alljoyn.AJ_ObjectsAdd(remote, nObj);
                }

                Log.i(TAG, "AllJoyn.registerObjects() Remote: " + remoteObjects.toString() + " => " + remote.toString());
            }

            alljoyn.AJ_RegisterObjects(local, remote);
            Log.i(TAG, "AllJoyn.registerObjects succeeded.");
            callbackContext.success("Registered objects!");
            return true;
        }
        else if (action.equals("addAdvertisedNameListener"))
        {
            Log.i(TAG, "AllJoyn.addAdvertisedNameListener");
            String serviceName = data.getString(0);
            AJ_Status status = alljoyn.AJ_BusFindAdvertisedName(bus, serviceName, alljoynConstants.AJ_BUS_START_FINDING);
            m_bStartTimer = true;

            if( status == AJ_Status.AJ_OK)
            {
                final long msgId = AJ_SIGNAL_FOUND_ADV_NAME;

                m_pMessageHandlers.put
                (
                    msgId,
                    new MsgHandler(callbackContext)
                    {
                        public boolean callback(_AJ_Message pMsg) throws JSONException
                        {
                            m_pMessageHandlers.remove(msgId);
                            _AJ_Arg arg = new _AJ_Arg();
                            alljoyn.AJ_UnmarshalArg(pMsg, arg);
                            Log.i(TAG, "FoundAdvertisedName(" + arg.getVal().getV_string() + ")");

                            // Send results
                            JSONObject responseDictionary = new JSONObject();
                            responseDictionary.put("name", arg.getVal().getV_string());
                            responseDictionary.put("sender", pMsg.getSender());
                            sendSuccessDictionary(responseDictionary, this.callbackContext, true, pMsg);
                            return true;
                        }
                    }
                );

                return true;
            }
            else
            {
                callbackContext.error("Failure starting find");
                return false;
            }
        }
        else if (action.equals("setSignalRule"))
        {
            Log.i(TAG, "AllJoyn.setSignalRule");
            AJ_Status status = AJ_Status.AJ_OK;
            String ruleString = data.getString(0);
            int rule = data.getInt(1);

            try
            {
                status = alljoyn.AJ_BusSetSignalRule(bus, ruleString, rule);
            }
            catch (Exception e)
            {
                Log.i(TAG, "Exception in setSignalRule: " + e.toString());
            }

            if( status == AJ_Status.AJ_OK)
            {
                callbackContext.success("setSignalRule successfully!");
                return true;
            }
            else
            {
                callbackContext.error("Error in setSignalRule: " + status.toString());
                return false;
            }
        }
        else if (action.equals("addInterfacesListener"))
        {
            Log.i(TAG, "AllJoyn.addInterfacesListener");
            AJ_Status status = AJ_Status.AJ_OK;

            if (status == AJ_Status.AJ_OK)
            {
                callbackContext.success("Yay!");
                return true;
            }
            else
            {
                callbackContext.error("Error: " + status.toString());
                return false;
            }
        }
        else if (action.equals("addListener"))
        {
            Log.i(TAG, "AllJoyn.addListener");
            AJ_Status status = AJ_Status.AJ_OK;
            JSONArray indexList = data.getJSONArray(0);
            String responseType = data.getString(1);

            if (indexList == null || responseType == null)
            {
                Log.i(TAG, "addListener: Invalid argument.");
                callbackContext.error("Error: " + status.toString());
                return false;
            }

            if(indexList.length() < 4)
            {
                Log.i(TAG, "addListener: Expected 4 indices in indexList");
                callbackContext.error("Error: " + status.toString());
                return false;
            }

            Log.i(TAG, "indexList=" + indexList.toString());
            Log.i(TAG, "responseType=" + responseType.toString());

            int listIndex = indexList.getInt(0);
            int objectIndex = indexList.getInt(1);
            int interfaceIndex = indexList.getInt(2);
            int memberIndex = indexList.getInt(3);
            final long msgId = AJ_Encode_Message_ID(listIndex, objectIndex, interfaceIndex, memberIndex);

            m_pMessageHandlers.put
            (
                msgId,
                new MsgHandler(callbackContext)
                {
                    public boolean callback(_AJ_Message pMsg) throws JSONException
                    {
                        m_pMessageHandlers.remove(msgId);
                        this.callbackContext.success("Yay!");
                        return true;
                    }
                }
            );

            m_bStartTimer = true;

            if( status == AJ_Status.AJ_OK)
            {
                callbackContext.success("Yay!");
                return true;
            }
            else
            {
                callbackContext.error("Error: " + status.toString());
                return false;
            }
        }
        else if (action.equals("joinSession"))
        {
            Log.i(TAG, "AllJoyn.joinSession");
            AJ_Status status = AJ_Status.AJ_OK;

            if (data.isNull(0))
            {
                callbackContext.error("JoinSession: Invalid Argument");
                return false;
            }

            JSONObject server = data.getJSONObject(0);
            int port = (Integer)server.get("port");
            final String name = (String)server.get("name");
            status = alljoyn.AJ_BusJoinSession(bus, name, port, null);

            if (status == AJ_Status.AJ_OK)
            {
                final long msgId = AJ_Reply_ID(AJ_METHOD_JOIN_SESSION);
                m_pMessageHandlers.put
                (
                    msgId,
                    new MsgHandler(callbackContext)
                    {
                        public boolean callback(_AJ_Message pMsg) throws JSONException
                        {
                            m_pMessageHandlers.remove(msgId);
                            Log.i(TAG, " -- Got reply to JoinSession ---");
                            Log.i(TAG, "MsgType: " + pMsg.getHdr().getMsgType());
                            long replyCode;
                            long sessionId;

                            if (pMsg.getHdr().getMsgType() == alljoynConstants.AJ_MSG_ERROR)
                            {
                                callbackContext.error("Failure joining session MSG ERROR");
                            }
                            else
                            {
                                JSONArray args = UnmarshalArgs(pMsg, "uu");
                                replyCode = args.getLong(0);
                                sessionId = args.getLong(1);
                                Log.i(TAG, "replyCode=" + replyCode +  " sessionId=" + sessionId);

                                if (replyCode == alljoynConstants.AJ_JOINSESSION_REPLY_SUCCESS)
                                {
                                    // Init responseArray
                                    JSONArray responseArray = new JSONArray();
                                    responseArray.put(sessionId);
                                    responseArray.put(name);
                                    sendSuccessArray(responseArray, this.callbackContext, false, pMsg);
                                    return true;
                                }
                                else
                                {
                                    if (replyCode == alljoynConstants.AJ_JOINSESSION_REPLY_ALREADY_JOINED)
                                    {
                                        // Init responseArray
                                        JSONArray responseArray = new JSONArray();
                                        responseArray.put(pMsg.getSessionId());
                                        responseArray.put(name);
                                        sendSuccessArray(responseArray, this.callbackContext, false, pMsg);
                                        return true;
                                    }
                                    else
                                    {
                                        callbackContext.error("Failure joining session replyCode = " + replyCode);
                                        return false;
                                    }
                                }
                            }

                            return true;
                        }
                    }
                );

                return true;
            }
            else
            {
                callbackContext.error("Error: " + status.toString());
                return false;
            }
        }
        else if (action.equals("leaveSession"))
        {
            Log.i(TAG, "AllJoyn.leaveSession");
            AJ_Status status = AJ_Status.AJ_OK;

            if (status == AJ_Status.AJ_OK)
            {
                callbackContext.success("Yay!");
                return true;
            }
            else
            {
                callbackContext.error("Error: " + status.toString());
                return false;
            }
        }
        else if (action.equals("invokeMember"))
        {
            Log.i(TAG, "AllJoyn.invokeMember");
            long sessionId = data.getLong(0);
            String destination = data.getString(1);
            String signature = data.getString(2);
            String path = data.getString(3);
            JSONArray indexList = data.getJSONArray(4);
            String parameterTypes = data.getString(5);
            JSONArray parameters = data.getJSONArray(6);
            final String outParameterSignature = data.getString(7);
            boolean isOwnSession = false;
            AJ_Status status = AJ_Status.AJ_OK;

            if (signature == null || indexList == null)
            {
                callbackContext.error("invokeMember: Invalid Argument");
                return false;
            }

            if (indexList.length() < 4)
            {
                callbackContext.error("invokeMember: Expected 4 indices in indexList");
                return false;
            }

            int listIndex = indexList.getInt(0);
            int objectIndex = indexList.getInt(1);
            int interfaceIndex = indexList.getInt(2);
            int memberIndex = indexList.getInt(3);

            if (sessionId == 0)
            {
                Log.i(TAG, "SessionId is 0, overriding listIndex to 1");
                listIndex = 1;
                isOwnSession = true;
            }

            long msgId = AJ_Encode_Message_ID(listIndex, objectIndex, interfaceIndex, memberIndex);
            Log.i(TAG, "Message id: " + msgId);

            SWIGTYPE_p_p_char memberSignature = new SWIGTYPE_p_p_char();
            SWIGTYPE_p_uint8_t isSecure = new SWIGTYPE_p_uint8_t();

            AJ_MemberType memberType = alljoyn.AJ_GetMemberType(msgId, memberSignature, isSecure);
            _AJ_Message msg = new _AJ_Message();

            if (path != null && path.length() > 0 && !path.equals("null")) // Checking null parameter passed from JS layer
            {
                status = alljoyn.AJ_SetProxyObjectPath(proxyObjects, msgId, path);

                if(status != AJ_Status.AJ_OK)
                {
                    Log.i(TAG, "AJ_SetProxyObjectPath failed with " + alljoyn.AJ_StatusText(status));
                    callbackContext.error("InvokeMember failure: " + alljoyn.AJ_StatusText(status));
                }
            }

            String destinationChars = "";

            if (destination != null)
            {
                destinationChars = destination;
            }

            if (memberType == AJ_MemberType.AJ_METHOD_MEMBER)
            {
                status = alljoyn.AJ_MarshalMethodCall(bus, msg, msgId, destinationChars, sessionId, 0, 0);

                if (status != AJ_Status.AJ_OK)
                {
                    Log.i(TAG, "Failure marshalling method call");
                    callbackContext.error("InvokeMember failure: " + alljoyn.AJ_StatusText(status));
                }

                if (parameterTypes != null && parameterTypes.length() > 0 && !parameterTypes.equals("null"))
                {
                    status = MarshalArgs(msg, parameterTypes, parameters);
                }
            }
            else if (memberType == AJ_MemberType.AJ_SIGNAL_MEMBER)
            {

            }
            else if (memberType == AJ_MemberType.AJ_PROPERTY_MEMBER)
            {
            }
            else
            {
                status = AJ_Status.AJ_ERR_FAILURE;
            }

            if (AJ_Status.AJ_OK == status)
            {
                status = alljoyn.AJ_DeliverMsg(msg);

                if (memberType != AJ_MemberType.AJ_SIGNAL_MEMBER)
                {
                    final long replyMsgId = AJ_Reply_ID(msgId);

                    m_pMessageHandlers.put
                    (
                        replyMsgId,
                        new MsgHandler(callbackContext)
                        {
                            public boolean callback(_AJ_Message pMsg) throws JSONException
                            {
                                AJ_Status status = AJ_Status.AJ_OK;
                                JSONArray outValues = null;
                                m_pMessageHandlers.remove(replyMsgId);

                                if (pMsg == null || pMsg.getHdr() == null || pMsg.getHdr().getMsgType() == alljoynConstants.AJ_MSG_ERROR)
                                {
                                    // Error
                                    callbackContext.error("Error" + alljoyn.AJ_StatusText(status));
                                    return true;
                                }

                                if (outParameterSignature != null && outParameterSignature.length() > 0 && !outParameterSignature.equals("null"))
                                {
                                    outValues = UnmarshalArgs(pMsg, outParameterSignature);
                                    status = AJ_Status.AJ_OK;
                                }

                                if (status != AJ_Status.AJ_OK)
                                {
                                    callbackContext.error("Failure unmarshalling response: " + alljoyn.AJ_StatusText(status));
                                    return true;
                                }

                                sendSuccessArray(outValues, this.callbackContext, false, pMsg);
                                return true;
                            }
                        }
                    );
                }

                return (AJ_Status.AJ_OK == status);
            }
        }

        return false;
    }

    long AJ_Encode_Message_ID(int o, int p, int i, int m)
    {
        return ((o << 24) | ((p) << 16) | ((i) << 8) | (m));
    }

    long AJ_Reply_ID(long id)
    {
        return ((id) | (long)((long)(AJ_RED_ID_FLAG) << 24));
    }

    JSONObject getMsgInfo(_AJ_Message pMsg) throws JSONException
    {
        JSONObject msgInfo = null;

        if (pMsg != null)
        {
            msgInfo = new JSONObject();

            if (pMsg.getSender() != null)
            {
                msgInfo.put("sender", pMsg.getSender());
            }

            if (pMsg.getSignature()!= null)
            {
                msgInfo.put("signature", pMsg.getSignature());
            }

            if (pMsg.getIface() != null)
            {
                msgInfo.put("iface", pMsg.getIface());
            }
        }

        return msgInfo;
    }

    void sendSuccessArray(JSONArray argumentValues, CallbackContext callbackContext, boolean keepCallback, _AJ_Message pMsg) throws JSONException
    {
        // Init message info
        JSONObject msgInfo = getMsgInfo(pMsg);

        // Init callback results
        JSONArray callbackResults = new JSONArray();
        callbackResults.put(msgInfo);
        callbackResults.put(argumentValues);
        callbackResults.put(null);

        // Send plugin result
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, callbackResults);
        pluginResult.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(pluginResult);
    }

    void sendSuccessDictionary(JSONObject argumentValues, CallbackContext callbackContext, boolean keepCallback, _AJ_Message pMsg) throws JSONException
    {
        // Init message info
        JSONObject msgInfo = getMsgInfo(pMsg);

        // Init callback results
        JSONArray callbackResults = new JSONArray();
        callbackResults.put(msgInfo);
        callbackResults.put(argumentValues);
        callbackResults.put(null);

        // Send plugin result
        PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, callbackResults);
        pluginResult.setKeepCallback(keepCallback);
        callbackContext.sendPluginResult(pluginResult);
    }

    public abstract class MsgHandler
    {
        public CallbackContext callbackContext;

        public MsgHandler(CallbackContext callbackContext)
        {
            this.callbackContext = callbackContext;
        }

        public abstract boolean callback(_AJ_Message pMsg) throws JSONException;
    }

    public AJ_Status MarshalArgs(_AJ_Message pMsg, String signature, JSONArray args) throws JSONException
    {
        AJ_Status status = AJ_Status.AJ_OK;

        for (int i = 0; i < signature.length(); i++)
        {
            _AJ_Arg arg = new _AJ_Arg();
            SWIGTYPE_p_uint32_t p_bool = new SWIGTYPE_p_uint32_t();
            SWIGTYPE_p_uint8_t p_uint8_t = new SWIGTYPE_p_uint8_t();
            SWIGTYPE_p_uint16_t p_uint16_t = new SWIGTYPE_p_uint16_t();
            SWIGTYPE_p_uint32_t p_uint32_t = new SWIGTYPE_p_uint32_t();
            SWIGTYPE_p_uint64_t p_uint64_t = new SWIGTYPE_p_uint64_t();
            SWIGTYPE_p_int16_t p_int16_t = new SWIGTYPE_p_int16_t();
            SWIGTYPE_p_int32_t p_int32_t = new SWIGTYPE_p_int32_t();
            SWIGTYPE_p_int64_t p_int64_t = new SWIGTYPE_p_int64_t();
            SWIGTYPE_p_double p_double = new SWIGTYPE_p_double();

            if (i == args.length() || args.get(i).equals(null))
            {
                return status;
            }

            char typeId = signature.charAt(i);

            switch (typeId)
            {
                case 'i':
                    alljoyn.setV_int32(p_int32_t, args.get(i).toString());
                    arg.getVal().setV_int32(p_int32_t);
                    break;

                case 'n':
                    alljoyn.setV_int16(p_int16_t, args.get(i).toString());
                    arg.getVal().setV_int16(p_int16_t);
                    break;

                case 'q':
                    alljoyn.setV_uint16(p_uint16_t, args.get(i).toString());
                    arg.getVal().setV_uint16(p_uint16_t);
                    break;

                case 't':
                    alljoyn.setV_uint64(p_uint64_t, args.get(i).toString());
                    arg.getVal().setV_uint64(p_uint64_t);
                    break;

                case 'u':
                    alljoyn.setV_uint32(p_uint32_t, args.get(i).toString());
                    arg.getVal().setV_uint32(p_uint32_t);
                    break;

                case 'x':
                    alljoyn.setV_int64(p_int64_t, args.get(i).toString());
                    arg.getVal().setV_int64(p_int64_t);
                    break;

                case 'y':
                    alljoyn.setV_byte(p_uint8_t, args.get(i).toString());
                    arg.getVal().setV_byte(p_uint8_t);
                    break;

                case 's':
                    arg.getVal().setV_string(args.getString(i));
                    break;
            }

            alljoyn.AJ_InitArg(arg, typeId, 0, arg.getVal().getV_data(), 0);
            status = alljoyn.AJ_MarshalArg(pMsg, arg);
        }

        return status;
    }

    public JSONArray UnmarshalArgs(_AJ_Message pMsg, String signature) throws JSONException
    {
        JSONArray args = new JSONArray();
        AJ_Status status = AJ_Status.AJ_OK;
        _AJ_Arg arg = new _AJ_Arg();

        for (int i = 0; i < signature.length(); i++)
        {
            alljoyn.AJ_UnmarshalArg(pMsg, arg);

            switch (signature.charAt(i))
            {
                case 'i':
                    args.put(i, Integer.parseInt(alljoyn.getV_int32(arg.getVal().getV_int32())));
                    break;

                case 'n':
                    args.put(i, Integer.parseInt(alljoyn.getV_int16(arg.getVal().getV_int16())));
                    break;

                case 'q':
                    args.put(i, Integer.parseInt(alljoyn.getV_uint16(arg.getVal().getV_uint16())));
                    break;

                case 't':
                    args.put(i, Long.parseLong(alljoyn.getV_uint64(arg.getVal().getV_uint64())));
                    break;

                case 'u':
                    args.put(i, Long.parseLong(alljoyn.getV_uint32(arg.getVal().getV_uint32())));
                    break;

                case 'x':
                    args.put(i, Long.parseLong(alljoyn.getV_int64(arg.getVal().getV_int64())));
                    break;

                case 'y':
                    args.put(i, Integer.parseInt(alljoyn.getV_byte(arg.getVal().getV_byte())));
                    break;

                case 's':
                    args.put(i, arg.getVal().getV_string());
                    break;
            }
        }

        return args;
    }
}
