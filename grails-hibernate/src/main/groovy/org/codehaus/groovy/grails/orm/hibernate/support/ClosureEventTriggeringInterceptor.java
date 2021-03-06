/*
 * Copyright 2003-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.grails.orm.hibernate.support;

import grails.util.CollectionUtils;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

import org.codehaus.groovy.grails.orm.hibernate.HibernateDatastore;
import org.codehaus.groovy.grails.orm.hibernate.SessionFactoryProxy;
import org.codehaus.groovy.grails.orm.hibernate.events.SaveOrUpdateEventListener;
import org.grails.datastore.mapping.core.Datastore;
import org.grails.datastore.mapping.engine.event.AbstractPersistenceEvent;
import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.SessionFactory;
import org.hibernate.action.EntityIdentityInsertAction;
import org.hibernate.action.EntityInsertAction;
import org.hibernate.engine.EntityKey;
import org.hibernate.engine.ForeignKeys;
import org.hibernate.engine.Nullability;
import org.hibernate.engine.Status;
import org.hibernate.engine.Versioning;
import org.hibernate.event.*;
import org.hibernate.event.def.AbstractSaveEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.type.Type;
import org.hibernate.type.TypeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.ReflectionUtils;

/**
 * Listens for Hibernate events and publishes corresponding Datastore events.
 *
 * @author Graeme Rocher
 * @author Lari Hotari
 * @author Burt Beckwith
 * @since 1.0
 */
public class ClosureEventTriggeringInterceptor extends SaveOrUpdateEventListener
       implements ApplicationContextAware,
                  PreLoadEventListener,
                  PostLoadEventListener,
                  PostInsertEventListener,
                  PostUpdateEventListener,
                  PostDeleteEventListener,
                  PreDeleteEventListener,
                  PreUpdateEventListener,
                  PreInsertEventListener {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private static final long serialVersionUID = 1;

    public static final Collection<String> IGNORED = CollectionUtils.newSet("version", "id");
    public static final String ONLOAD_EVENT = "onLoad";
    public static final String ONLOAD_SAVE = "onSave";
    public static final String BEFORE_LOAD_EVENT = "beforeLoad";
    public static final String BEFORE_INSERT_EVENT = "beforeInsert";
    public static final String AFTER_INSERT_EVENT = "afterInsert";
    public static final String BEFORE_UPDATE_EVENT = "beforeUpdate";
    public static final String AFTER_UPDATE_EVENT = "afterUpdate";
    public static final String BEFORE_DELETE_EVENT = "beforeDelete";
    public static final String AFTER_DELETE_EVENT = "afterDelete";
    public static final String AFTER_LOAD_EVENT = "afterLoad";

    private Method markInterceptorDirtyMethod;
    private ApplicationContext ctx;
    private Map<SessionFactory, HibernateDatastore> datastores;

    public ClosureEventTriggeringInterceptor() {
        try {
            markInterceptorDirtyMethod = ReflectionUtils.findMethod(AbstractSaveEventListener.class, "markInterceptorDirty", new Class[]{Object.class, EntityPersister.class, EventSource.class});
            ReflectionUtils.makeAccessible(markInterceptorDirtyMethod);
        } catch (Exception e) {
            // ignore
        }
    }

    public void setDatastores(Map<SessionFactory, HibernateDatastore> datastores) {
        this.datastores = datastores;
    }

    @Override
    public void onSaveOrUpdate(SaveOrUpdateEvent hibernateEvent) throws HibernateException {
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.SaveOrUpdateEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity()));
        super.onSaveOrUpdate(hibernateEvent);
    }

    public void onPreLoad(PreLoadEvent hibernateEvent) {
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.PreLoadEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity()));
    }

    public void onPostLoad(PostLoadEvent hibernateEvent) {
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.PostLoadEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity()));
    }

    public boolean onPreInsert(PreInsertEvent hibernateEvent) {
        AbstractPersistenceEvent event = new org.grails.datastore.mapping.engine.event.PreInsertEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity());
        publishEvent(hibernateEvent, event);
        return event.isCancelled();
    }

    public void onPostInsert(PostInsertEvent hibernateEvent) {
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.PostInsertEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity()));
    }

    public boolean onPreUpdate(PreUpdateEvent hibernateEvent) {
        AbstractPersistenceEvent event = new org.grails.datastore.mapping.engine.event.PreUpdateEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity());
        publishEvent(hibernateEvent, event);
        return event.isCancelled();
    }

    public void onPostUpdate(PostUpdateEvent hibernateEvent) {
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.PostUpdateEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity()));
    }

    public boolean onPreDelete(PreDeleteEvent hibernateEvent) {
        AbstractPersistenceEvent event = new org.grails.datastore.mapping.engine.event.PreDeleteEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity());
        publishEvent(hibernateEvent, event);
        return event.isCancelled();
    }

    public void onPostDelete(PostDeleteEvent hibernateEvent) {
        publishEvent(hibernateEvent, new org.grails.datastore.mapping.engine.event.PostDeleteEvent(
                findDatastore(hibernateEvent), hibernateEvent.getEntity()));
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        ctx = applicationContext;
    }

    private void publishEvent(AbstractEvent hibernateEvent, AbstractPersistenceEvent mappingEvent) {
        mappingEvent.setNativeEvent(hibernateEvent);
        ctx.publishEvent(mappingEvent);
    }

    private Datastore findDatastore(AbstractEvent hibernateEvent) {
        SessionFactory sessionFactory = hibernateEvent.getSession().getSessionFactory();
        if (!(sessionFactory instanceof SessionFactoryProxy)) {
            // should always be the case
            for (Map.Entry<SessionFactory, HibernateDatastore> entry : datastores.entrySet()) {
                SessionFactory sf = entry.getKey();
                if (sf instanceof SessionFactoryProxy) {
                    if (((SessionFactoryProxy)sf).getCurrentSessionFactory() == sessionFactory) {
                        return entry.getValue();
                    }
                }
            }
        }

        Datastore datastore = datastores.get(sessionFactory);
        if (datastore == null && datastores.size() == 1) {
            datastore = datastores.values().iterator().next();
        }
        return datastore;
    }

    /*
     * TODO: This is a horrible hack due to a bug in Hibernate's post-insert event processing (HHH-3904)
     */
    @Override
    protected Serializable performSaveOrReplicate(Object entity, EntityKey key, EntityPersister persister, boolean useIdentityColumn, Object anything, EventSource source, boolean requiresImmediateIdAccess) {
        validate(entity, persister, source);

        Serializable id = key == null ? null : key.getIdentifier();

        boolean inTxn = source.getJDBCContext().isTransactionInProgress();
        boolean shouldDelayIdentityInserts = !inTxn && !requiresImmediateIdAccess;

        // Put a placeholder in entries, so we don't recurse back and try to save() the
        // same object again. QUESTION: should this be done before onSave() is called?
        // likewise, should it be done before onUpdate()?
        source.getPersistenceContext().addEntry(
                entity,
                Status.SAVING,
                null,
                null,
                id,
                null,
                LockMode.WRITE,
                useIdentityColumn,
                persister,
                false,
                false);

        cascadeBeforeSave(source, persister, entity, anything);

        if (useIdentityColumn && !shouldDelayIdentityInserts) {
            log.trace("executing insertions");
            source.getActionQueue().executeInserts();
        }

        Object[] values = persister.getPropertyValuesToInsert(entity, getMergeMap(anything), source);
        Type[] types = persister.getPropertyTypes();

        boolean substitute = substituteValuesIfNecessary(entity, id, values, persister, source);

        if (persister.hasCollections()) {
            substitute = substitute || visitCollectionsBeforeSave(entity, id, values, types, source);
        }

        if (substitute) {
            persister.setPropertyValues(entity, values, source.getEntityMode());
        }

        TypeHelper.deepCopy(
                values,
                types,
                persister.getPropertyUpdateability(),
                values,
                source);

        new ForeignKeys.Nullifier(entity, false, useIdentityColumn, source)
                .nullifyTransientReferences(values, types);
        new Nullability(source).checkNullability(values, persister, false);

        if (useIdentityColumn) {
            EntityIdentityInsertAction insert = new EntityIdentityInsertAction(
                    values, entity, persister, source, shouldDelayIdentityInserts);
            if (!shouldDelayIdentityInserts) {
                log.debug("executing identity-insert immediately");
                source.getActionQueue().execute(insert);
                id = insert.getGeneratedId();
                if (id != null) {
                    // As of HHH-3904, if the id is null the operation was vetoed so we bail
                    key = new EntityKey(id, persister, source.getEntityMode());
                    source.getPersistenceContext().checkUniqueness(key, entity);
                }
            }
            else {
                log.debug("delaying identity-insert due to no transaction in progress");
                source.getActionQueue().addAction(insert);
                key = insert.getDelayedEntityKey();
            }
        }

        if (key != null) {
            Object version = Versioning.getVersion(values, persister);
            source.getPersistenceContext().addEntity(
                    entity,
                    (persister.isMutable() ? Status.MANAGED : Status.READ_ONLY),
                    values,
                    key,
                    version,
                    LockMode.WRITE,
                    useIdentityColumn,
                    persister,
                    isVersionIncrementDisabled(),
                    false);
            //source.getPersistenceContext().removeNonExist(new EntityKey(id, persister, source.getEntityMode()));

            if (!useIdentityColumn) {
                source.getActionQueue().addAction(
                        new EntityInsertAction(id, values, entity, version, persister, source));
            }

            cascadeAfterSave(source, persister, entity, anything);
            // Very unfortunate code, but markInterceptorDirty is private. Once HHH-3904 is resolved remove this overridden method!
            if (markInterceptorDirtyMethod != null) {
                ReflectionUtils.invokeMethod(markInterceptorDirtyMethod, this, entity, persister, source);
            }
        }

        return id;
    }
}
