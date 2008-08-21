/*
 *  The Sun Project JXTA(TM) Software License
 *  
 *  Copyright (c) 2001-2007 Sun Microsystems, Inc. All rights reserved.
 *  
 *  Redistribution and use in source and binary forms, with or without 
 *  modification, are permitted provided that the following conditions are met:
 *  
 *  1. Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *  
 *  2. Redistributions in binary form must reproduce the above copyright notice, 
 *     this list of conditions and the following disclaimer in the documentation 
 *     and/or other materials provided with the distribution.
 *  
 *  3. The end-user documentation included with the redistribution, if any, must 
 *     include the following acknowledgment: "This product includes software 
 *     developed by Sun Microsystems, Inc. for JXTA(TM) technology." 
 *     Alternately, this acknowledgment may appear in the software itself, if 
 *     and wherever such third-party acknowledgments normally appear.
 *  
 *  4. The names "Sun", "Sun Microsystems, Inc.", "JXTA" and "Project JXTA" must 
 *     not be used to endorse or promote products derived from this software 
 *     without prior written permission. For written permission, please contact 
 *     Project JXTA at http://www.jxta.org.
 *  
 *  5. Products derived from this software may not be called "JXTA", nor may 
 *     "JXTA" appear in their name, without prior written permission of Sun.
 *  
 *  THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
 *  INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND 
 *  FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL SUN 
 *  MICROSYSTEMS OR ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, 
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT 
 *  LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, 
 *  OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF 
 *  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING 
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, 
 *  EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *  
 *  JXTA is a registered trademark of Sun Microsystems, Inc. in the United 
 *  States and other countries.
 *  
 *  Please see the license information page at :
 *  <http://www.jxta.org/project/www/license.html> for instructions on use of 
 *  the license in source files.
 *  
 *  ====================================================================

 *  This software consists of voluntary contributions made by many individuals 
 *  on behalf of Project JXTA. For more information on Project JXTA, please see 
 *  http://www.jxta.org.
 *  
 *  This license is based on the BSD license adopted by the Apache Foundation. 
 */

package net.jxta.impl.content;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.jxta.content.Content;
import net.jxta.content.ContentID;
import net.jxta.document.Advertisement;
import net.jxta.id.ID;
import net.jxta.peergroup.PeerGroup;
import net.jxta.protocol.ModuleImplAdvertisement;
import net.jxta.content.ContentService;
import net.jxta.content.ContentProviderListener;
import net.jxta.content.ContentProvider;
import net.jxta.content.ContentProviderSPI;
import net.jxta.content.ContentProviderEvent;
import net.jxta.content.ContentShare;
import net.jxta.content.ContentTransfer;
import net.jxta.content.TransferException;
import net.jxta.exception.PeerGroupException;
import net.jxta.logging.Logging;
import net.jxta.platform.ModuleSpecID;
import net.jxta.protocol.ContentShareAdvertisement;
import net.jxta.protocol.ModuleSpecAdvertisement;

/**
 * Reference implementation of the ContentService.  This implementation
 * manages the listener list, tracks active shares, and uses the Jar
 * service provider interface to locate transfer provider implementations
 * which will perform the real work.
 */
public class ContentServiceImpl implements ContentService {

    /**
     * Well known service spec identifier: reference implementation of the
     * ContentService.
     */
    public final static ModuleSpecID MODULE_SPEC_ID =
            ModuleSpecID.create(URI.create(
            "urn:jxta:uuid-DDC5CA55578E4AB99A0AA81D2DC6EF3F"
            + "3F7E9F18B5D84DD58D21CE9E37E19E6C06"));

    /**
     * Logger.
     */
    private static final Logger LOG = Logger.getLogger(
            ContentServiceImpl.class.getName());
    
    /**
     * List of providers which are started and ready for use.
     */
    private final List<ContentProviderSPI> providers = 
            new CopyOnWriteArrayList<ContentProviderSPI>();
    
    /**
     * List of our listeners.
     */
    private final List<ContentProviderListener> listeners =
            new CopyOnWriteArrayList<ContentProviderListener>();
    
    /**
     * Lifecycle manager responsible for getting provider instances
     * into the correct operational state.
     */
    private final ModuleLifecycleManager<ContentProviderSPI> manager =
            new ModuleLifecycleManager<ContentProviderSPI>();
    
    /**
     * List of providers which are registered and waiting for the
     * service to be initialized before being added to the lifecycle
     * manager.  After initialization, this list is nulled.
     */
    private List<ContentProviderSPI> waitingForInit = locateProviders();
    
    /**
     * Implementation adv given to us via init().
     */
    private ModuleImplAdvertisement implAdv = null;

    /**
     * Peer group given to us via init().
     */
    private PeerGroup group;
    
    /**
     * Object to lock against when accessing member vars.
     */
    private final Object lock = new Object();
    
    /**
     * Flag indicatin that this instancce has been initialized.
     */
    private boolean initialized = false;
    
    /**
     * Flag indicating that this instance has been started.
     */
    private boolean started = false;

    /**
     * Default constructor.
     */
    public ContentServiceImpl() {
        // Track available providers indirectly via the lifecycle manager
        manager.addModuleLifecycleListener(
                new ModuleLifecycleListener() {

            /**
             * {@inheritDoc}
             */
            public void unhandledPeerGroupException(
                    ModuleLifecycleTracker subject, PeerGroupException mlcx) {
                if (Logging.SHOW_WARNING && LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING, "Uncaught exception", mlcx);
                }
            }

            /**
             * {@inheritDoc}
             */
            public void moduleLifecycleStateUpdated(
                    ModuleLifecycleTracker subject,
                    ModuleLifecycleState newState) {
                ContentProviderSPI provider =
                        (ContentProviderSPI) subject.getModule();
                LOG.fine("Content provider lifecycle state update: "
                        + provider + " --> " + newState);
                if (newState == ModuleLifecycleState.STARTED) {
                    providers.add(provider);
                } else {
                    providers.remove(provider);
                }
            }
            
        });
    }

    //////////////////////////////////////////////////////////////////////////
    // Module interface methods:

    /**
     * {@inheritDoc}
     */
    public void init(
            PeerGroup group, ID assignedID, Advertisement adv) {
        List<ContentProviderSPI> toAdd;
        synchronized(lock) {
            if (initialized) {
                return;
            }
            initialized = true;
            this.group = group;
            this.implAdv = (ModuleImplAdvertisement) adv;
            toAdd = waitingForInit;
            waitingForInit = null;
        }


        if (Logging.SHOW_CONFIG && LOG.isLoggable(Level.CONFIG)) {
            StringBuilder configInfo = new StringBuilder();

            configInfo.append( "Configuring Content Service : "
                    + assignedID );

            configInfo.append( "\n\tImplementation :" );
            if (implAdv != null) {
                configInfo.append( "\n\t\tModule Spec ID: "
                        + implAdv.getModuleSpecID());
                configInfo.append( "\n\t\tImpl Description : "
                        + implAdv.getDescription());
                configInfo.append( "\n\t\tImpl URI : " + implAdv.getUri());
                configInfo.append( "\n\t\tImpl Code : " + implAdv.getCode());
            }

            configInfo.append( "\n\tGroup Params :" );
            configInfo.append( "\n\t\tGroup : " + group.getPeerGroupName() );
            configInfo.append( "\n\t\tGroup ID : " + group.getPeerGroupID() );
            configInfo.append( "\n\t\tPeer ID : " + group.getPeerID() );

            configInfo.append( "\n\tProviders: ");
            for (ContentProviderSPI provider : providers) {
                configInfo.append( "\n\t\tProvider: " + provider );
            }
            
            LOG.config( configInfo.toString() );
        }
        
        // Provider initialization
        for (ContentProviderSPI provider : toAdd) {
            addContentProvider(provider);
        }
        manager.init();
    }

    /**
     * {@inheritDoc}
     */
    public int startApp(String args[]) {
        synchronized(lock) {
            if (started) {
                return START_OK;
            }
            started = true;
        }
        
        manager.start();
        if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
            LOG.fine( "Content Service started.");
        }

        return START_OK;
    }

    /**
     * {@inheritDoc}
     */
    public void stopApp() {
        synchronized(lock) {
            if (!started) {
                return;
            }
            started = false;
        }

        manager.stop();
        if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
            LOG.fine( "Content Service stopped.");
        }
    }

    //////////////////////////////////////////////////////////////////////////
    // Service interface methods:

    /**
     * {@inheritDoc}
     */
    public Advertisement getImplAdvertisement() {
        synchronized(lock) {
            return implAdv;
        }
    }

    /**
     * {@inheritDoc}
     */
    public ContentService getInterface() {
        return  (ContentService) ModuleWrapperFactory.newWrapper(
                new Class[] { ContentService.class },
                this);
    }

    //////////////////////////////////////////////////////////////////////////
    // ContentService interface methods:

    /**
     * {@inheritDoc}
     */
    public void addContentProvider(ContentProviderSPI provider) {
        boolean addToManager = false;
        synchronized(lock) {
            if (initialized) {
                // Add to manager and let the manager event add to list
                addToManager = true;
            } else {
                // Add to pending list
                waitingForInit.add(provider);
            }
        }
        if (addToManager) {
            // We try to be as correct and complete as possible here...
            Advertisement adv = provider.getImplAdvertisement();
            ID asgnID;
            if (adv instanceof ModuleSpecAdvertisement) {
                ModuleSpecAdvertisement specAdv =
                        (ModuleSpecAdvertisement) adv;
                asgnID = specAdv.getModuleSpecID();
            } else if (adv instanceof ModuleImplAdvertisement) {
                ModuleImplAdvertisement mimpAdv =
                        (ModuleImplAdvertisement) adv;
                asgnID = mimpAdv.getModuleSpecID();
            } else {
                asgnID = adv.getID();
            }
            manager.addModule(provider, group, asgnID, adv, null);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void removeContentProvider(ContentProvider provider) {
        if (!(provider instanceof ContentProviderSPI)) {
            /*
             * Can't cast so we can't use.  Note that the add/remove
             * asymmetry is intentional since getContentProviders()
             * returns the ContentProvider sub-interface to prevent
             * user access to SPI methods.
             */
            return;
        }
        ContentProviderSPI spi = (ContentProviderSPI) provider;
        boolean removeFromManager = false;
        synchronized(lock) {
            if (initialized) {
                // List is maintained via manager
                removeFromManager = true;
            } else {
                // Remove from pending list
                waitingForInit.remove(provider);
            }
        }
        if (removeFromManager) {
            manager.removeModule(spi, true);
        }
    }

    /**
     * {@inheritDoc}
     */
    public List<ContentProvider> getContentProviders() {
        /*
         * NOTE mcumings 20061120:  Note that this could also be implemented
         * using Collections.unmodifiableList(), but having the returned list
         * run the potential of effectively changing over time (it would be
         * read-through) led me to select a full copy instead.
         */
        List<ContentProvider> result =
                new ArrayList<ContentProvider>(providers);
        return result;
    }

    //////////////////////////////////////////////////////////////////////////
    // ContentProvider interface methods:

    /**
     * {@inheritDoc}
     */
    public void addContentProviderListener(
            ContentProviderListener listener) {
        listeners.add(listener);
    }

    /**
     * {@inheritDoc}
     */
    public void removeContentProviderListener(
            ContentProviderListener listener) {
        listeners.remove(listener);
    }

    /**
     * {@inheritDoc}
     */
    public ContentTransfer retrieveContent(ContentID contentID) {
        try {
            return new TransferAggregator(this, providers, contentID);
        } catch (TransferException transx) {
            if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Returning null due to exception", transx);
            }
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public ContentTransfer retrieveContent(ContentShareAdvertisement adv) {
        try {
            return new TransferAggregator(this, providers, adv);
        } catch (TransferException transx) {
            if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Returning null due to exception", transx);
            }
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public List<ContentShare> shareContent(Content content) {
        List<ContentShare> result = null;
        List<ContentShare> subShares;

        for (ContentProvider provider : providers) {
            try {
                subShares = provider.shareContent(content);
                if (subShares == null) {
                    continue;
                }

                if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
                    LOG.fine("Content with ID '" + content.getContentID() +
                            "' being shared by provider: " + provider);
                }

                if (result == null) {
                    result = new ArrayList<ContentShare>();
                }

                result.addAll(subShares);
            } catch (UnsupportedOperationException uox) {
                if (Logging.SHOW_FINEST && LOG.isLoggable(Level.FINEST)) {
                    LOG.finest("Ignoring provider which doesn't support "
                            + "share operation: " + provider);
                }
            }
        }

        if (result != null) {
            fireContentShared(result);
        }

        return result;
    }

    /**
     * {@inheritDoc}
     */
    public boolean unshareContent(ContentID contentID) {
        boolean unshared = false;

        for (ContentProvider provider : providers) {
            unshared |= provider.unshareContent(contentID);
        }

        if (unshared) {
            fireContentUnshared(contentID);
        }
        return unshared;
    }

    /**
     * {@inheritDoc}
     */
    public void findContentShares(
            int maxNum, ContentProviderListener listener) {
        List<ContentProviderListener> findListeners =
                new ArrayList<ContentProviderListener>();
        findListeners.add(listener);

        EventAggregator aggregator =
                new EventAggregator(findListeners, providers);
        aggregator.dispatchFindRequest(maxNum);
    }


    //////////////////////////////////////////////////////////////////////////
    // Private methods:

    /**
     * Notify listeners of a new Content share.
     *
     * @param shares list of shares for the Content
     */
    private void fireContentShared(List<ContentShare> shares) {
        ContentProviderEvent event = null;

        for (ContentProviderListener listener : listeners) {
            try {
                if (event == null) {
                    event = new ContentProviderEvent(this, shares);
                }
                listener.contentShared(event);
            } catch (Throwable thr) {
                if (Logging.SHOW_WARNING && LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING,
                            "Uncaught throwable from listener", thr);
                }
            }
        }
    }

    /**
     * Notify listeners of a new Content share.
     *
     * @param id Content ID
     */
    private void fireContentUnshared(ContentID id) {
        ContentProviderEvent event = null;

        for (ContentProviderListener listener : listeners) {
            try {
                if (event == null) {
                    event = new ContentProviderEvent(this, id);
                }
                listener.contentUnshared(event);
            } catch (Throwable thr) {
                if (Logging.SHOW_WARNING && LOG.isLoggable(Level.WARNING)) {
                    LOG.log(Level.WARNING,
                            "Uncaught throwable from listener", thr);
                }
            }
        }
    }

    /**
     * Locate all implementations of the ContentServiceProviderSPI interface
     * using the Jar service provider interface mechanism.
     *
     * @return list of content provider implementations
     */
    private List<ContentProviderSPI> locateProviders() {
        ContentProviderSPI provider;
        List<ContentProviderSPI> result =
                new CopyOnWriteArrayList<ContentProviderSPI>();
        ClassLoader loader = getClass().getClassLoader();
        if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
            LOG.fine("Locating providers");
        }
        try {
            Enumeration resources = loader.getResources(
                    "META-INF/services/" + ContentProviderSPI.class.getName());
            while (resources.hasMoreElements()) {
                URL resURL = (URL) resources.nextElement();
                if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
                    LOG.fine("   Provider source:" + resURL);
                }
                try {
                    InputStreamReader inReader =
                            new InputStreamReader(resURL.openStream());
                    BufferedReader reader = new BufferedReader(inReader);
                    String str;

                    while ((str = reader.readLine()) != null) {
                        int idx = str.indexOf('#');
                        if (idx >= 0) {
                            str = str.substring(0, idx);
                        }
                        str = str.trim();
                        if (str.length() == 0) {
                            // Probably a commented line
                            continue;
                        }

                        try {
                            Class cl = loader.loadClass(str);
                            provider = (ContentProviderSPI) cl.newInstance();
                            result.add(provider);
                            if (Logging.SHOW_FINE && LOG.isLoggable(Level.FINE)) {
                                LOG.fine("Added provider: " + str);
                            }
                        } catch (ClassNotFoundException cnfx) {
                            if (Logging.SHOW_SEVERE && LOG.isLoggable(Level.SEVERE)) {
                                LOG.log(Level.SEVERE,
                                        "Could not load service provider", cnfx);
                            }
                            // Continue to next provider class name
                        } catch (InstantiationException instx) {
                            if (Logging.SHOW_SEVERE && LOG.isLoggable(Level.SEVERE)) {
                                LOG.log(Level.SEVERE,
                                        "Could not load service provider", instx);
                            }
                            // Continue to next provider class name
                        } catch (IllegalAccessException iaccx) {
                            if (Logging.SHOW_SEVERE && LOG.isLoggable(Level.SEVERE)) {
                                LOG.log(Level.SEVERE,
                                        "Could not load service provider", iaccx);
                            }
                            // Continue to next provider class name
                        }
                    }
                } catch (IOException iox2) {
                    if (Logging.SHOW_SEVERE && LOG.isLoggable(Level.SEVERE)) {
                        LOG.log(Level.SEVERE, "Could not inspect resource", iox2);
                    }
                    // Continue to next resource URL
                }
            }
        } catch (IOException iox) {
            if (Logging.SHOW_SEVERE && LOG.isLoggable(Level.SEVERE)) {
                LOG.log(Level.SEVERE, "Could not probe for providers", iox);
            }
        }

        return result;
    }
}
