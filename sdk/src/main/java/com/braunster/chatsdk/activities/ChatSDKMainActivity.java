package com.braunster.chatsdk.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.astuetz.pagersslidingtabstrip.PagerSlidingTabStrip;
import com.braunster.chatsdk.R;
import com.braunster.chatsdk.Utils.Debug;
import com.braunster.chatsdk.Utils.ExitHelper;
import com.braunster.chatsdk.Utils.NotificationUtils;
import com.braunster.chatsdk.activities.abstracted.ChatSDKAbstractChatActivity;
import com.braunster.chatsdk.adapter.PagerAdapterTabs;
import com.braunster.chatsdk.dao.BMessage;
import com.braunster.chatsdk.dao.BThread;
import com.braunster.chatsdk.fragments.ChatSDKBaseFragment;
import com.braunster.chatsdk.network.BDefines;
import com.braunster.chatsdk.network.BNetworkManager;
import com.braunster.chatsdk.network.events.AppEventListener;
import com.braunster.chatsdk.object.ChatSDKThreadPool;
import com.braunster.chatsdk.object.UIUpdater;

import org.apache.commons.lang3.StringUtils;

import java.io.File;


public class ChatSDKMainActivity extends ChatSDKBaseActivity {

    // TODO add option to save up app in external storage. http://developer.android.com/guide/topics/data/install-location.html
    // TODO stack notification like whatsapp and gamil http://developer.android.com/reference/android/app/Notification.InboxStyle.html

    private static final String TAG = ChatSDKMainActivity.class.getSimpleName();
    private static boolean DEBUG = Debug.MainActivity;
    private ExitHelper exitHelper;
    private PagerSlidingTabStrip tabs;
    private ViewPager pager;
    private PagerAdapterTabs adapter;

    private static final String FIRST_TIME_IN_APP = "First_Time_In_App";
    public static final String PAGE_ADAPTER_POS = "page_adapter_pos";

    public static final String Action_Contacts_Added = "com.braunster.androidchatsdk.action.contact_added";
    public static final String Action_clear_data = "com.braunster.androidchatsdk.action.logged_out";
    public static final String Action_Refresh_Fragment = "com.braunster.androidchatsdk.action.refresh_fragment";

    private static final String LAST_MSG_TIMESTAMP = "last_open_msg_timestamp";
    private long msgTimestamp = 0;

    private int pageAdapterPos = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        exitHelper = new ExitHelper(this);

        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
            // Activity was brought to front and not created,
            // Thus finishing this will get us to the last viewed activity
            finish();
            return;
        }

        if (DEBUG) Log.v(TAG, "onCreate");

        setContentView(R.layout.chat_sdk_activity_view_pager);

        firstTimeInApp();
        initViews();

        enableCheckOnlineOnResumed(true);

        if (!fromLoginActivity && savedInstanceState != null)
        {
            if (DEBUG) Log.v(TAG, "Saved Instance is not null, "  + savedInstanceState.getInt(PAGE_ADAPTER_POS));
            pager.setCurrentItem(savedInstanceState.getInt(PAGE_ADAPTER_POS));
        }

        Intent intent = getIntent();

        if (intent.getExtras()!= null && intent.getExtras().getBoolean(ChatSDKChatActivity.FROM_PUSH, false))
        {
            msgTimestamp = intent.getExtras().getLong(ChatSDKAbstractChatActivity.MSG_TIMESTAMP);

            // We are checking to see if this message was not opened before.
            if (savedInstanceState == null || msgTimestamp > savedInstanceState.getLong(LAST_MSG_TIMESTAMP, -1))
            {
                intent.getExtras().remove(ChatSDKAbstractChatActivity.FROM_PUSH);
                startChatActivityForID(intent.getExtras().getLong(ChatSDKAbstractChatActivity.THREAD_ID));
            }

            return;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (DEBUG) Log.v(TAG, "onPause");

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (DEBUG) Log.v(TAG, "onResume");

        ChatSDKThreadPool.getInstance().execute(new Runnable() {
            @Override
            public void run() {
                getNetworkAdapter().getEventManager().removeEventByTag(appEventListener.getTag());
                getNetworkAdapter().getEventManager().addEvent(appEventListener);

                tabs.setOnPageChangeListener(new ViewPager.OnPageChangeListener() {
                    private int lastPage = 0;
                    private int refreshContactsInterval = 4000;
                    private long lastContactsRefresh = 0;

                    @Override
                    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
//                if (DEBUG) Log.v(TAG, "onPageScrolled");
                    }

                    @Override
                    public void onPageSelected(int position) {
                        if (DEBUG)
                            Log.v(TAG, "onPageSelected, Pos: " + position + ", Last: " + lastPage);

                        pageAdapterPos = position;

                        lastPage = position;
                    }

                    @Override
                    public void onPageScrollStateChanged(int state) {
//                if (DEBUG) Log.v(TAG, "onPageScrollStateChanged");
                    }
                });

                IntentFilter intentFilter = new IntentFilter(Action_Contacts_Added);
                intentFilter.addAction(Action_clear_data);
                intentFilter.addAction(Action_Refresh_Fragment);

                registerReceiver(mainReceiver, intentFilter);
            }
        });

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (DEBUG) Log.v(TAG, "onNewIntent");

        if (intent.getExtras()!= null && intent.getExtras().getBoolean(ChatSDKChatActivity.FROM_PUSH, false))
        {
            intent.getExtras().remove(ChatSDKAbstractChatActivity.FROM_PUSH);
            startChatActivityForID(intent.getExtras().getLong(ChatSDKAbstractChatActivity.THREAD_ID));
            return;
        }

        if (adapter != null)
        {
            ChatSDKBaseFragment pro = getFragment(PagerAdapterTabs.Profile), conv = getFragment(PagerAdapterTabs.Conversations);

            if (conv!=null)
                conv.refreshOnBackground();

            if (pro != null)
                pro.refresh();
        }
    }

    private AppEventListener appEventListener = new AppEventListener("MainActivity") {
        private final int uiUpdateDelay = 1000, messageDelay = 3000;
        private UIUpdater uiUpdaterMessages;

        @Override
        public boolean onMessageReceived(final BMessage message) {
            if (DEBUG) Log.v(TAG, "onMessageReceived");

            // Only notify for private threads.
            if (message.getBThreadOwner().getTypeSafely() == BThread.Type.Public) {
                return false;
            }

            // Make sure the message that incoming is not the user message.
            if (message.getBUserSender().getEntityID().equals(
                    BNetworkManager.sharedManager().getNetworkAdapter().currentUser().getEntityID()))
                return false;

            if (uiUpdaterMessages != null)
                uiUpdaterMessages.setKilled(true);

            handler.removeCallbacks(uiUpdaterMessages);

            uiUpdaterMessages = new UIUpdater(){

                @Override
                public void run() {
                    if (!isKilled())
                    {
                        // We check to see that the ChatActivity is not listening to this messages so we wont alert twice.
                        if (!getNetworkAdapter().getEventManager().isEventTagExist(ChatSDKChatActivity.MessageListenerTAG + message.getOwnerThread())) {
                            // Checking if the message has a sender with a name, Also if the message was read.
                            if (message.getBUserSender().getMetaName() != null && !message.wasRead())
                                NotificationUtils.createMessageNotification(ChatSDKMainActivity.this, message);
                        }
                    }
                }
            };

            handler.postDelayed(uiUpdaterMessages, messageDelay);

            return false;
        }
    };

    static final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(PAGE_ADAPTER_POS, pageAdapterPos);
        outState.putLong(LAST_MSG_TIMESTAMP, msgTimestamp);
    }

    private void initViews(){
        pager = (ViewPager) findViewById(R.id.pager);

        tabs = (PagerSlidingTabStrip) findViewById(R.id.tabs);

        adapter = new PagerAdapterTabs(getFragmentManager());

        pager.setAdapter(adapter);

        tabs.setViewPager(pager);

        pager.setOffscreenPageLimit(3);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_chat_sdk, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.android_settings) {

            return true;
        }
        else   if (item.getItemId() == R.id.contact_developer) {
            if(StringUtils.isNotEmpty(BDefines.ContactDeveloper_Email))
            {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts(
                        "mailto", BDefines.ContactDeveloper_Email, null));
                emailIntent.putExtra(Intent.EXTRA_SUBJECT, BDefines.ContactDeveloper_Subject);
                startActivity(Intent.createChooser(emailIntent, BDefines.ContactDeveloper_DialogTitle));
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // If the register did not had a chance to register due to ordination change.
        try{
            unregisterReceiver(mainReceiver);
        }catch (IllegalArgumentException e){

        }
    }

    private void firstTimeInApp(){
        //TODO handle no SDCARD!
        if (PreferenceManager.getDefaultSharedPreferences(this).getBoolean(FIRST_TIME_IN_APP, true))
        {
            if (DEBUG) Log.d(TAG, "First time in app");
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)){
                if (DEBUG) Log.d(TAG, "No SDCARD");
            } else {
                File directory = new File(Environment.getExternalStorageDirectory()+ File.separator+"AndroidChatSDK");
                if (DEBUG) Log.d(TAG, "Creating app directory");
                    directory.mkdirs();
            }

            PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(FIRST_TIME_IN_APP, false).apply();
        }
    }

    /** Refresh the contacts fragment when a contact added action is received.
     *  Clear Fragments data when logged out.
     *  Refresh Fragment when wanted.*/
    private BroadcastReceiver mainReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Action_Contacts_Added))
            {
                ChatSDKBaseFragment contacts = getFragment(PagerAdapterTabs.Contacts);

                if (contacts != null)
                    contacts.refreshOnBackground();

                if (intent.getExtras().containsKey(ChatSDKSearchActivity.USER_IDS_LIST))
                {
                    String[] ids = intent.getStringArrayExtra(ChatSDKSearchActivity.USER_IDS_LIST);
                    for (String id : ids)
                        getNetworkAdapter().getEventManager().handleUsersDetailsChange(id);
                }
            }
            else if (intent.getAction().equals(Action_clear_data))
            {
                clearData();
            }
            else if (intent.getAction().equals(Action_Refresh_Fragment))
            {
                if (intent.getExtras() == null)
                    return;

                if (!intent.getExtras().containsKey(PAGE_ADAPTER_POS))
                    return;

                int fragment = intent.getExtras().getInt(PAGE_ADAPTER_POS);

                ChatSDKBaseFragment frag = getFragment(fragment);

                if (frag!= null)
                    frag.refresh();

            }
            else if (intent.getAction().equals(ChatSDKAbstractChatActivity.ACTION_CHAT_CLOSED))
            {
                getFragment(PagerAdapterTabs.Conversations).loadDataOnBackground();
            }
        }
    };

    private void clearData(){
        ChatSDKBaseFragment contacts = getFragment(PagerAdapterTabs.Contacts);

        if (contacts != null)
            contacts.clearData();

        ChatSDKBaseFragment conv = getFragment(PagerAdapterTabs.Conversations);

        if (conv != null)
            conv.clearData();

        ChatSDKBaseFragment pro = getFragment(PagerAdapterTabs.Profile);

        if (pro != null)
            pro.clearData();
    }

    /* Exit Stuff*/
    @Override
    public void onBackPressed() {
        exitHelper.triggerExit();
    }

    /** After screen orientation chage the getItem from the fragment page adapter is no null but it is not visible to the user
     *  so we have to use this workaround so when we call any method on the wanted fragment the fragment will respond.
     *  http://stackoverflow.com/a/7393477/2568492*/
    private ChatSDKBaseFragment getFragment(int index){
        return ((ChatSDKBaseFragment) getFragmentManager().findFragmentByTag("android:switcher:" + R.id.pager + ":" + index));
    }
}