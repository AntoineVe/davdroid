/*******************************************************************************
 * Copyright (c) 2013 Richard Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 ******************************************************************************/
package at.bitfire.davdroid.resource;

import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;
import net.fortuna.ical4j.model.Dur;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.ValidationException;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.CuType;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.Duration;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.ExRule;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.Priority;
import net.fortuna.ical4j.model.property.RDate;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.Status;
import net.fortuna.ical4j.vcard.property.Uid;

import org.apache.commons.lang.StringUtils;
import org.dmfs.provider.tasks.TaskContract;
import org.dmfs.provider.tasks.TaskContract.Tasks;

import android.accounts.Account;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Attendees;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.provider.ContactsContract;
import android.util.Log;
import android.widget.Toast;
import at.bitfire.davdroid.R;
import at.bitfire.davdroid.resource.Event.TYPE;
import at.bitfire.davdroid.syncadapter.ServerInfo;

public class LocalCalendar extends LocalCollection<Event> {
	private static final String TAG = "davdroid.LocalCalendar";

	@Getter
	protected long id;
	@Getter
	protected String path, cTag;

	protected static String COLLECTION_COLUMN_CTAG = Calendars.CAL_SYNC1;

	private static Context ctx;

	/* database fields */

	@Override
	protected Uri entriesURI() {
		return syncAdapterURI(Events.CONTENT_URI);
	}

	protected String entryColumnAccountType() {
		return Events.ACCOUNT_TYPE;
	}

	protected String entryColumnAccountName() {
		return Events.ACCOUNT_NAME;
	}

	protected String entryColumnParentID() {
		return Events.CALENDAR_ID;
	}

	protected String entryColumnID() {
		return Events._ID;
	}

	protected String entryColumnRemoteName() {
		return Events._SYNC_ID;
	}

	protected String entryColumnETag() {
		return Events.SYNC_DATA1;
	}

	protected String entryColumnDirty() {
		return Events.DIRTY;
	}

	protected String entryColumnDeleted() {
		return Events.DELETED;
	}

	@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
	protected String entryColumnUID() {
		return (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) ? Events.UID_2445
				: Events.SYNC_DATA2;
	}

	/* class methods, constructor */

	@SuppressLint("InlinedApi")
	public static void create(Account account, ContentResolver resolver,
			ServerInfo.ResourceInfo info) throws RemoteException {
		ContentProviderClient client = resolver
				.acquireContentProviderClient(CalendarContract.AUTHORITY);
		// ContentProviderClient task = resolver
		// .acquireContentProviderClient(TaskContract.AUTHORITY);

		int color = 0xFFC3EA6E; // fallback: "DAVdroid green"
		if (info.getColor() != null) {
			Pattern p = Pattern.compile("#(\\p{XDigit}{6})(\\p{XDigit}{2})?");
			Matcher m = p.matcher(info.getColor());
			if (m.find()) {
				int color_rgb = Integer.parseInt(m.group(1), 16);
				int color_alpha = m.group(2) != null ? (Integer.parseInt(
						m.group(2), 16) & 0xFF) : 0xFF;
				color = (color_alpha << 24) | color_rgb;
			}
		}

		ContentValues values = new ContentValues();
		values.put(Calendars.ACCOUNT_NAME, account.name);
		values.put(Calendars.ACCOUNT_TYPE, account.type);
		values.put(Calendars.NAME, info.getPath());
		values.put(Calendars.CALENDAR_DISPLAY_NAME, info.getTitle());
		values.put(Calendars.CALENDAR_COLOR, color);
		values.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
		values.put(Calendars.ALLOWED_AVAILABILITY, Events.AVAILABILITY_BUSY
				+ "," + Events.AVAILABILITY_FREE + ","
				+ Events.AVAILABILITY_TENTATIVE);
		values.put(Calendars.ALLOWED_ATTENDEE_TYPES, Attendees.TYPE_NONE + ","
				+ Attendees.TYPE_REQUIRED + "," + Attendees.TYPE_OPTIONAL + ","
				+ Attendees.TYPE_RESOURCE);
		values.put(Calendars.OWNER_ACCOUNT, account.name);
		values.put(Calendars.SYNC_EVENTS, 1);
		values.put(Calendars.VISIBLE, 1);
		if (info.getTimezone() != null)
			values.put(Calendars.CALENDAR_TIME_ZONE, info.getTimezone());

		Log.i(TAG, "Inserting calendar: " + values.toString() + " -> "
				+ calendarsURI(account).toString());
		client.insert(calendarsURI(account), values);
	}

	public static LocalCalendar[] findAll(Account account,
			ContentProviderClient providerClient, Context ctx_)
			throws RemoteException {
		ctx = ctx_;
		Cursor cursor = providerClient.query(calendarsURI(account),
				new String[] { Calendars._ID, Calendars.NAME,
						COLLECTION_COLUMN_CTAG }, Calendars.DELETED + "=0 AND "
						+ Calendars.SYNC_EVENTS + "=1", null, null);

		LinkedList<LocalCalendar> calendars = new LinkedList<LocalCalendar>();
		while (cursor != null && cursor.moveToNext())
			calendars.add(new LocalCalendar(account, providerClient, cursor
					.getInt(0), cursor.getString(1), cursor.getString(2)));
		return calendars.toArray(new LocalCalendar[0]);
	}

	public LocalCalendar(Account account, ContentProviderClient providerClient,
			int id, String path, String cTag) throws RemoteException {
		super(account, providerClient);
		this.id = id;
		this.path = path;
		this.cTag = cTag;
	}

	@Override
	public void add(Event resource)
			throws net.fortuna.ical4j.model.ValidationException {
		if (resource.getType() == TYPE.VEVENT) {
			super.add(resource);
		} else if (resource.getType() == TYPE.VTODO) {
			int idx = pendingOperations.size();
			for (Uri uri : tasksURI(account)) {
				pendingOperations.add(buildEntry(
						ContentProviderOperation.newInsert(uri), resource)
						.withYieldAllowed(true).build());

				addDataRows(resource, -1, idx);
			}
		} else {
			throw new ValidationException("Unkown Type");
		}
	};

	/* collection operations */

	@Override
	public void setCTag(String cTag) {
		pendingOperations.add(ContentProviderOperation
				.newUpdate(ContentUris.withAppendedId(calendarsURI(), id))
				.withValue(COLLECTION_COLUMN_CTAG, cTag).build());
	}

	@Override
	public void delete(Resource resource) {
		if (resource instanceof Event) {
			Event e = (Event) resource;
			if (e.getType() == TYPE.VEVENT) {
				super.delete(e);
			} else if (e.getType() == TYPE.VTODO) {
				for (Uri uri : tasksURI(account)) {
					pendingOperations.add(ContentProviderOperation
							.newDelete(
									ContentUris.withAppendedId(uri,
											resource.getLocalID()))
							.withYieldAllowed(true).build());
				}
			} else {
				Log.wtf(TAG, "wrong type");
			}
		} else {
			Log.wtf(TAG, "wrong type");
		}

	};

	/* content provider (= database) querying */

	@Override
	public Event findById(long localID, String remoteName, String eTag,
			boolean populate) throws RemoteException {
		return findById(localID, remoteName, eTag, populate, TYPE.VEVENT);
	}

	public Event findById(long localID, String remoteName, String eTag,
			boolean populate, TYPE t) throws RemoteException {
		Event e = new Event(localID, remoteName, eTag, t);
		if (populate)
			populate(e);
		return e;
	}

	@Override
	public void updateByRemoteName(Event remoteResource)
			throws RemoteException, ValidationException {
		if (remoteResource.getType() == TYPE.VEVENT) {
			super.updateByRemoteName(remoteResource);
		} else {
			Event localResource = findByRemoteName(remoteResource.getName());
			remoteResource.validate();
			for (Uri uri : tasksURI(account)) {
				pendingOperations
						.add(buildEntry(
								ContentProviderOperation.newUpdate(ContentUris
										.withAppendedId(uri,
												localResource.getLocalID())),
								remoteResource)
								.withValue(Tasks.SYNC1,
										remoteResource.getETag())
								.withYieldAllowed(true).build());
			}

			// TODO add support for this
			// removeDataRows(localResource);
			// addDataRows(remoteResource, localResource.getLocalID(), -1);
		}
	};

	@Override
	public Event findByRemoteName(String remoteName) throws RemoteException {
		Event r = null;
		Cursor cursor = providerClient
				.query(entriesURI(), new String[] { entryColumnID(),
						entryColumnRemoteName(), entryColumnETag() },
						Events.CALENDAR_ID + "=? AND "
								+ entryColumnRemoteName() + "=?", new String[] {
								String.valueOf(id), remoteName }, null);
		if (cursor != null && cursor.moveToNext())
			r = new Event(cursor.getLong(0), cursor.getString(1),
					cursor.getString(2), TYPE.VEVENT);
		else {
			for (Uri uri : tasksURI(account)) {
				cursor = ctx.getContentResolver()
						.query(uri,
								new String[] { Tasks._ID, Tasks._SYNC_ID,
										Tasks.SYNC1 }, /*
														 * Tasks . LIST_ID +
														 * "=? AND " +
														 */
								Tasks._SYNC_ID + "='?'",
								new String[] { /*
												 * String.valueOf(id) ,
												 */remoteName }, null);
				if (cursor != null && cursor.moveToNext()) {
					r = new Event(cursor.getLong(0), cursor.getString(1),
							cursor.getString(2), TYPE.VTODO);
					break;
				}
			}
		}
		cursor.close();
		return r;
	}

	@Override
	public Resource[] findDeleted() throws RemoteException {
		LinkedList<Resource> deleted = new LinkedList<Resource>();
		deleted.addAll(Arrays.asList(super.findDeleted()));
		String where = Tasks._DELETED + "=1";
		// if (Tasks.LIST_ID != null)
		// where += " AND " + Tasks.LIST_ID + "=" + String.valueOf(getId());
		for (Uri uri : tasksURI(account)) {
			Cursor cursor = ctx.getContentResolver().query(uri,
					new String[] { Tasks._ID, Tasks._SYNC_ID, Tasks.SYNC1 },
					where, null, null);
			while (cursor != null && cursor.moveToNext())
				deleted.add(findById(cursor.getLong(0), cursor.getString(1),
						cursor.getString(2), false, TYPE.VTODO));
			cursor.close();
		}
		return deleted.toArray(new Resource[0]);
	}

	@Override
	public Resource[] findNew() throws RemoteException {
		LinkedList<Resource> fresh = new LinkedList<Resource>();
		fresh.addAll(Arrays.asList(super.findNew()));
		// TODO fix in Mirakel: Do only create uuids if account=tw-sync!!
		String where = Tasks._DIRTY + "=1 AND " + Tasks._SYNC_ID + " IS NULL";
		// if (Tasks.LIST_ID != null)
		// where += " AND " + Tasks.LIST_ID + "=" + String.valueOf(getId());
		for (Uri uri : tasksURI(account)) {
			Cursor cursor = ctx.getContentResolver().query(uri,
					new String[] { Tasks._ID }, where, null, null);
			while (cursor != null && cursor.moveToNext()) {
				String uid = UUID.randomUUID().toString(), resourceName = uid
						+ fileExtension();
				Resource resource = findById(cursor.getLong(0), resourceName,
						null, true); // new Event(cursor.getLong(0),
										// resourceName, null);
				resource.setUid(uid);

				// new record: set generated resource name in database
				pendingOperations.add(ContentProviderOperation
						.newUpdate(
								ContentUris.withAppendedId(Tasks.CONTENT_URI,
										resource.getLocalID()))
						.withValue(Tasks._SYNC_ID, resourceName).build());

				fresh.add(resource);
			}
			cursor.close();
		}
		return fresh.toArray(new Resource[0]);
	}

	@Override
	public Resource[] findDirty() throws RemoteException {
		LinkedList<Resource> dirty = new LinkedList<Resource>();
		dirty.addAll(Arrays.asList(super.findDirty()));
		String where = Tasks._DIRTY + "=1";
		// if (Tasks.LIST_ID != null)
		// where += " AND " + Tasks.LIST_ID + "=" + String.valueOf(getId());
		for (Uri uri : tasksURI(account)) {
			Cursor cursor = ctx.getContentResolver().query(uri,
					new String[] { Tasks._ID, Tasks._SYNC_ID, Tasks.SYNC1 },
					where, null, null);
			while (cursor != null && cursor.moveToNext())
				dirty.add(findById(cursor.getLong(0), cursor.getString(1),
						cursor.getString(2), true));
			cursor.close();
		}
		return dirty.toArray(new Resource[0]);
	}

	@Override
	public void commit() throws RemoteException,
			android.content.OperationApplicationException {
		if (!pendingOperations.isEmpty()) {
			Log.i(TAG, "Committing " + pendingOperations.size() + " operations");
			ContentResolver resolver = ctx.getContentResolver();
			// TODO remove this(there must be a better way to do this...)
			ArrayList<ContentProviderOperation> t = new ArrayList<ContentProviderOperation>();
			for (ContentProviderOperation op : pendingOperations) {
				t.add(op);
				try {
					resolver.applyBatch(CalendarContract.AUTHORITY, t);
				} catch (Exception e) {
					try {
						resolver.applyBatch(TaskContract.AUTHORITY, t);
					} catch (Exception e1) {
						throw new RuntimeException();
					}
				}
				t.clear();
			}
			pendingOperations.clear();
		}
	};

	@Override
	public void populate(Resource resource) throws RemoteException {
		Event e = (Event) resource;
		if (e.isPopulated())
			return;
		boolean success = false;
		if (e.getType() != TYPE.VTODO) {
			success = populateEvent(e);
			e.setType(TYPE.VEVENT);
		}
		if (!success) {
			success = populateToDo(e);
			if (success) {
				e.setType(TYPE.VTODO);
			} else {
				Log.d(TAG, "Not VEVENT or VTODO");
				throw new RuntimeException();
			}
		}
	}

	private boolean populateToDo(Event e) throws RemoteException {
		for (Uri uri : tasksURI(account)) {
			Cursor cursor = ctx.getContentResolver().query(
					ContentUris.withAppendedId(uri,
							e.getLocalID()),
					new String[] {
					/* 0 */Tasks.TITLE, Tasks.LOCATION, Tasks.DESCRIPTION,
							Tasks.DUE, Tasks.STATUS, Tasks.PRIORITY, Tasks._ID,
							Tasks.ACCOUNT_NAME, Tasks._SYNC_ID, Tasks.SYNC1 },
					null, null, null);
			if (cursor != null && cursor.moveToNext()) {
				e.setUid(cursor.getString(7));

				e.setSummary(cursor.getString(0));
				e.setLocation(cursor.getString(1));
				e.setDescription(cursor.getString(2));
				if (!cursor.isNull(3))
					e.setDue(cursor.getLong(3), null);
				// status
				switch (cursor.getInt(4)) {
				case 2:// Tasks.STATUS_COMPLETED:
					e.setStatus(Status.VTODO_COMPLETED);
					break;
				case 3:// Tasks.STATUS_CANCELLED:
					e.setStatus(Status.VTODO_CANCELLED);
					break;
				case 1:// Tasks.STATUS_IN_PROCESS:
					e.setStatus(Status.VTODO_IN_PROCESS);
					break;
				default:
				case 0:// Tasks.STATUS_NEEDS_ACTION:
					e.setStatus(Status.VTODO_NEEDS_ACTION);
					break;

				}
				e.setPriority(new Priority(cursor.getInt(5)));
				cursor.close();
				return true;
			}
			cursor.close();
		}
		return false;
	}

	private boolean populateEvent(Event e) throws RemoteException {
		Cursor cursor = providerClient.query(
				ContentUris.withAppendedId(entriesURI(), e.getLocalID()),
				new String[] {
				/* 0 */Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION,
				/* 3 */Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE,
						Events.EVENT_END_TIMEZONE, Events.ALL_DAY,
						/* 8 */Events.STATUS, Events.ACCESS_LEVEL,
						/* 10 */Events.RRULE, Events.RDATE, Events.EXRULE,
						Events.EXDATE,
						/* 14 */Events.HAS_ATTENDEE_DATA, Events.ORGANIZER,
						Events.SELF_ATTENDEE_STATUS,
						/* 17 */entryColumnUID(), Events.DURATION }, null,
				null, null);
		if (cursor != null && cursor.moveToNext()) {
			e.setUid(cursor.getString(17));

			e.setSummary(cursor.getString(0));
			e.setLocation(cursor.getString(1));
			e.setDescription(cursor.getString(2));
			long tsStart = cursor.getLong(3), tsEnd = cursor.getLong(4);

			String tzId;
			if (cursor.getInt(7) != 0) { // ALL_DAY != 0
				tzId = null; // -> use UTC
			} else {
				// use the start time zone for the end time, too
				// because the Samsung Planner UI allows the user to change the
				// time zone
				// but it will change the start time zone only
				tzId = cursor.getString(5);
				// tzIdEnd = cursor.getString(6);
			}
			e.setDtStart(tsStart, tzId);
			if (tsEnd != 0)
				e.setDtEnd(tsEnd, tzId);

			// recurrence
			try {
				String duration = cursor.getString(18);
				if (duration != null)
					e.setDuration(new Duration(new Dur(duration)));

				String strRRule = cursor.getString(10);
				if (strRRule != null)
					e.setRrule(new RRule(strRRule));

				String strRDate = cursor.getString(11);
				if (strRDate != null) {
					RDate rDate = new RDate();
					rDate.setValue(strRDate);
					e.setRdate(rDate);
				}

				String strExRule = cursor.getString(12);
				if (strExRule != null) {
					ExRule exRule = new ExRule();
					exRule.setValue(strExRule);
					e.setExrule(exRule);
				}

				String strExDate = cursor.getString(13);
				if (strExDate != null) {
					// ignored, see
					// https://code.google.com/p/android/issues/detail?id=21426
					ExDate exDate = new ExDate();
					exDate.setValue(strExDate);
					e.setExdate(exDate);
				}

			} catch (ParseException ex) {
				Log.e(TAG, "Couldn't parse recurrence rules, ignoring");
			}

			// status
			switch (cursor.getInt(8)) {
			case Events.STATUS_CONFIRMED:
				e.setStatus(Status.VEVENT_CONFIRMED);
				break;
			case Events.STATUS_TENTATIVE:
				e.setStatus(Status.VEVENT_TENTATIVE);
				break;
			case Events.STATUS_CANCELED:
				e.setStatus(Status.VEVENT_CANCELLED);
			}

			// attendees
			if (cursor.getInt(14) != 0) { // has attendees
				try {
					e.setOrganizer(new Organizer("mailto:"
							+ cursor.getString(15)));
				} catch (URISyntaxException ex) {
					Log.e(TAG, "Error parsing organizer URI, ignoring");
				}

				Uri attendeesUri = Attendees.CONTENT_URI
						.buildUpon()
						.appendQueryParameter(
								ContactsContract.CALLER_IS_SYNCADAPTER, "true")
						.build();
				Cursor c = providerClient.query(attendeesUri, new String[] {
				/* 0 */Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME,
						Attendees.ATTENDEE_TYPE,
						/* 3 */Attendees.ATTENDEE_RELATIONSHIP,
						Attendees.STATUS }, Attendees.EVENT_ID + "=?",
						new String[] { String.valueOf(e.getLocalID()) }, null);
				while (c != null && c.moveToNext()) {
					try {
						Attendee attendee = new Attendee("mailto:"
								+ c.getString(0));
						ParameterList params = attendee.getParameters();

						String cn = c.getString(1);
						if (cn != null)
							params.add(new Cn(cn));

						// type
						int type = c.getInt(2);
						params.add((type == Attendees.TYPE_RESOURCE) ? CuType.RESOURCE
								: CuType.INDIVIDUAL);

						// role
						int relationship = c.getInt(3);
						switch (relationship) {
						case Attendees.RELATIONSHIP_ORGANIZER:
							params.add(Role.CHAIR);
							break;
						case Attendees.RELATIONSHIP_ATTENDEE:
						case Attendees.RELATIONSHIP_PERFORMER:
						case Attendees.RELATIONSHIP_SPEAKER:
							params.add((type == Attendees.TYPE_REQUIRED) ? Role.REQ_PARTICIPANT
									: Role.OPT_PARTICIPANT);
							break;
						case Attendees.RELATIONSHIP_NONE:
							params.add(Role.NON_PARTICIPANT);
						}

						// status
						int status = Attendees.ATTENDEE_STATUS_NONE;
						if (relationship == Attendees.RELATIONSHIP_ORGANIZER) // we
																				// are
																				// organizer
							status = cursor.getInt(16);
						else
							status = c.getInt(4);

						switch (status) {
						case Attendees.ATTENDEE_STATUS_INVITED:
							params.add(PartStat.NEEDS_ACTION);
							break;
						case Attendees.ATTENDEE_STATUS_ACCEPTED:
							params.add(PartStat.ACCEPTED);
							break;
						case Attendees.ATTENDEE_STATUS_DECLINED:
							params.add(PartStat.DECLINED);
							break;
						case Attendees.ATTENDEE_STATUS_TENTATIVE:
							params.add(PartStat.TENTATIVE);
							break;
						}

						e.addAttendee(attendee);
					} catch (URISyntaxException ex) {
						Log.e(TAG,
								"Couldn't parse attendee member URI, ignoring member");
					}
				}
			}

			// classification
			switch (cursor.getInt(9)) {
			case Events.ACCESS_CONFIDENTIAL:
			case Events.ACCESS_PRIVATE:
				e.setForPublic(false);
				break;
			case Events.ACCESS_PUBLIC:
				e.setForPublic(true);
			}

			// reminders
			Uri remindersUri = Reminders.CONTENT_URI
					.buildUpon()
					.appendQueryParameter(
							ContactsContract.CALLER_IS_SYNCADAPTER, "true")
					.build();
			Cursor c = providerClient.query(remindersUri, new String[] {
			/* 0 */Reminders.MINUTES, Reminders.METHOD }, Reminders.EVENT_ID
					+ "=?", new String[] { String.valueOf(e.getLocalID()) },
					null);
			while (c != null && c.moveToNext()) {
				VAlarm alarm = new VAlarm(new Dur(0, 0, -c.getInt(0), 0));

				PropertyList props = alarm.getProperties();
				switch (c.getInt(1)) {
				/*
				 * case Reminders.METHOD_EMAIL: props.add(Action.EMAIL); break;
				 */
				default:
					props.add(Action.DISPLAY);
					props.add(new Description(e.getSummary()));
				}
				e.addAlarm(alarm);
			}
			return true;
		} else {
			return false;
		}
	}

	@Override
	public void clearDirty(Resource resource) {
		Event e = (Event) resource;
		if (e.getType() == TYPE.VEVENT)
			super.clearDirty(resource);
		else {
			for (Uri uri : tasksURI(account)) {
				pendingOperations.add(ContentProviderOperation
						.newUpdate(
								ContentUris.withAppendedId(uri,
										resource.getLocalID()))
						.withValue(Tasks._DIRTY, 0).build());
			}
		}
	}

	public void deleteAllExceptRemoteNames(Resource[] remoteResources) {
		String where;

		if (remoteResources.length != 0) {
			List<String> sqlFileNames = new LinkedList<String>();
			for (Resource res : remoteResources)
				sqlFileNames.add(DatabaseUtils.sqlEscapeString(res.getName()));
			where = entryColumnRemoteName() + " NOT IN ("
					+ StringUtils.join(sqlFileNames, ",") + ")";
		} else
			where = entryColumnRemoteName() + " IS NOT NULL";

		Builder builder = ContentProviderOperation.newDelete(entriesURI())
				.withSelection(
						entryColumnParentID() + "=? AND (" + where + ")",
						new String[] { String.valueOf(id) });
		pendingOperations.add(builder.withYieldAllowed(true).build());
		where = "";
		if (remoteResources.length != 0) {
			List<String> sqlFileNames = new LinkedList<String>();
			for (Resource res : remoteResources)
				sqlFileNames.add(DatabaseUtils.sqlEscapeString(res.getName()));
			where = Tasks._SYNC_ID + " NOT IN ("
					+ StringUtils.join(sqlFileNames, ",") + ")";
		} else
			where = entryColumnRemoteName() + " IS NOT NULL";
		for (Uri uri : tasksURI(account)) {
			builder = ContentProviderOperation.newDelete(uri).withSelection(
					where, new String[] { String.valueOf(id) });
		}
		pendingOperations.add(builder.withYieldAllowed(true).build());
	}

	/* private helper methods */

	@Override
	protected String fileExtension() {
		return ".ics";
	}

	protected static Uri calendarsURI(Account account) {
		return Calendars.CONTENT_URI
				.buildUpon()
				.appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
				.appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
				.appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER,
						"true").build();
	}

	protected static List<Uri> tasksURI(Account account) {
		List<Uri> uris = new ArrayList<Uri>();
		PackageManager pm = ctx.getPackageManager();
		try {
			PackageInfo mirakel = pm.getPackageInfo("de.azapps.mirakelandroid",
					PackageManager.GET_PROVIDERS);
			if (mirakel != null && mirakel.versionCode > 18) {
				uris.add(Tasks.CONTENT_URI
						.buildUpon()
						.appendQueryParameter(TaskContract.ACCOUNT_NAME,
								account.name)
						.appendQueryParameter(TaskContract.ACCOUNT_TYPE,
								account.type)
						.appendQueryParameter(
								TaskContract.CALLER_IS_SYNCADAPTER, "true")
						.build());
			}
		} catch (NameNotFoundException e) {
			Log.w(TAG, "Mirakel not found");
		}
		try {
			PackageInfo dmfs = pm.getPackageInfo("org.dmfs.provider.tasks",
					PackageManager.GET_PROVIDERS);
			if (dmfs != null) {
				uris.add(Uri.parse("content://" + TaskContract.AUTHORITY_DMFS + "/" + Tasks.CONTENT_URI_PATH)
						.buildUpon()
						.appendQueryParameter(TaskContract.ACCOUNT_NAME,
								account.name)
						.appendQueryParameter(TaskContract.ACCOUNT_TYPE,
								account.type)
						.appendQueryParameter(
								TaskContract.CALLER_IS_SYNCADAPTER, "true")
						.build());
			}
		} catch (NameNotFoundException e) {
			Log.w(TAG, "dmfs not found");
		}
		if (uris.size() == 0) {
			//TODO show tost here
			//Toast.makeText(ctx, R.string.install_taskprovider,
			//		Toast.LENGTH_LONG).show();
		}
		return uris;
	}

	protected Uri calendarsURI() {
		return calendarsURI(account);
	}

	/* content builder methods */

	@Override
	protected Builder buildEntry(Builder builder, Event event) {

		if (event.getType() == TYPE.VEVENT)
			builder = buildVEVENT(builder, event);
		else if (event.getType() == TYPE.VTODO)
			builder = buildVTODO(builder, event);
		else
			throw new RuntimeException();

		return builder;
	}

	private Builder buildVTODO(Builder builder, Event todo) {
		builder = builder.withValue(Tasks.LIST_ID, id)
				.withValue(Tasks.TITLE, todo.getSummary())
				.withValue(Tasks.SYNC1, todo.getETag())
				.withValue(Tasks._SYNC_ID, todo.getName());
		// .withValue(Tasks.U, value)//TODO uid??
		if (todo.getDue() != null)
			builder = builder.withValue(Tasks.DUE, todo.getDueInMillis());
		if (todo.getPriority() != null)
			builder = builder.withValue(Tasks.PRIORITY, todo.getPriority()
					.getLevel());
		if (todo.getDescription() != null)
			builder = builder.withValue(Tasks.DESCRIPTION,
					todo.getDescription());

		return builder;
	}

	private Builder buildVEVENT(Builder builder, Event event) {
		builder = builder
				.withValue(Events.CALENDAR_ID, id)
				.withValue(entryColumnRemoteName(), event.getName())
				.withValue(entryColumnETag(), event.getETag())
				.withValue(entryColumnUID(), event.getUid())
				.withValue(Events.ALL_DAY, event.isAllDay() ? 1 : 0)
				.withValue(Events.DTSTART, event.getDtStartInMillis())
				.withValue(Events.DTEND, event.getDtEndInMillis())
				.withValue(Events.EVENT_TIMEZONE, event.getDtStartTzID())
				.withValue(Events.HAS_ATTENDEE_DATA,
						event.getAttendees().isEmpty() ? 0 : 1);

		if (event.getDtEndTzID() != null)
			builder = builder.withValue(Events.EVENT_END_TIMEZONE,
					event.getDtEndTzID());

		if (event.getRrule() != null)
			builder = builder.withValue(Events.RRULE, event.getRrule()
					.getValue());
		if (event.getRdate() != null)
			builder = builder.withValue(Events.RDATE, event.getRdate()
					.getValue());
		if (event.getExrule() != null)
			builder = builder.withValue(Events.EXRULE, event.getExrule()
					.getValue());
		if (event.getExdate() != null)
			builder = builder.withValue(Events.EXDATE, event.getExdate()
					.getValue());

		if (event.getSummary() != null)
			builder = builder.withValue(Events.TITLE, event.getSummary());
		if (event.getLocation() != null)
			builder = builder.withValue(Events.EVENT_LOCATION,
					event.getLocation());
		if (event.getDescription() != null)
			builder = builder.withValue(Events.DESCRIPTION,
					event.getDescription());

		Status status = event.getStatus();
		if (status != null) {
			int statusCode = Events.STATUS_TENTATIVE;
			if (status == Status.VEVENT_CONFIRMED)
				statusCode = Events.STATUS_CONFIRMED;
			else if (status == Status.VEVENT_CANCELLED)
				statusCode = Events.STATUS_CANCELED;
			builder = builder.withValue(Events.STATUS, statusCode);
		}

		if (event.getForPublic() != null)
			builder = builder.withValue(Events.ACCESS_LEVEL, event
					.getForPublic() ? Events.ACCESS_PUBLIC
					: Events.ACCESS_PRIVATE);
		return builder;
	}

	@Override
	protected void addDataRows(Event event, long localID, int backrefIdx) {
		for (Attendee attendee : event.getAttendees())
			pendingOperations.add(buildAttendee(
					newDataInsertBuilder(Attendees.CONTENT_URI,
							Attendees.EVENT_ID, localID, backrefIdx), attendee)
					.build());
		for (VAlarm alarm : event.getAlarms())
			pendingOperations.add(buildReminder(
					newDataInsertBuilder(Reminders.CONTENT_URI,
							Reminders.EVENT_ID, localID, backrefIdx), alarm)
					.build());
	}

	@Override
	protected void removeDataRows(Event event) {
		pendingOperations.add(ContentProviderOperation
				.newDelete(syncAdapterURI(Attendees.CONTENT_URI))
				.withSelection(Attendees.EVENT_ID + "=?",
						new String[] { String.valueOf(event.getLocalID()) })
				.build());
		pendingOperations.add(ContentProviderOperation
				.newDelete(syncAdapterURI(Reminders.CONTENT_URI))
				.withSelection(Reminders.EVENT_ID + "=?",
						new String[] { String.valueOf(event.getLocalID()) })
				.build());
	}

	@SuppressLint("InlinedApi")
	protected Builder buildAttendee(Builder builder, Attendee attendee) {
		Uri member = Uri.parse(attendee.getValue());
		String email = member.getSchemeSpecificPart();

		Cn cn = (Cn) attendee.getParameter(Parameter.CN);
		if (cn != null)
			builder = builder.withValue(Attendees.ATTENDEE_NAME, cn.getValue());

		int type = Attendees.TYPE_NONE;

		CuType cutype = (CuType) attendee.getParameter(Parameter.CUTYPE);
		if (cutype == CuType.RESOURCE)
			type = Attendees.TYPE_RESOURCE;
		else {
			Role role = (Role) attendee.getParameter(Parameter.ROLE);
			int relationship;
			if (role == Role.CHAIR)
				relationship = Attendees.RELATIONSHIP_ORGANIZER;
			else {
				relationship = Attendees.RELATIONSHIP_ATTENDEE;
				if (role == Role.OPT_PARTICIPANT)
					type = Attendees.TYPE_OPTIONAL;
				else if (role == Role.REQ_PARTICIPANT)
					type = Attendees.TYPE_REQUIRED;
			}
			builder = builder.withValue(Attendees.ATTENDEE_RELATIONSHIP,
					relationship);
		}

		int status = Attendees.ATTENDEE_STATUS_NONE;
		PartStat partStat = (PartStat) attendee
				.getParameter(Parameter.PARTSTAT);
		if (partStat == PartStat.NEEDS_ACTION)
			status = Attendees.ATTENDEE_STATUS_INVITED;
		else if (partStat == PartStat.ACCEPTED)
			status = Attendees.ATTENDEE_STATUS_ACCEPTED;
		else if (partStat == PartStat.DECLINED)
			status = Attendees.ATTENDEE_STATUS_DECLINED;
		else if (partStat == PartStat.TENTATIVE)
			status = Attendees.ATTENDEE_STATUS_TENTATIVE;

		return builder.withValue(Attendees.ATTENDEE_EMAIL, email)
				.withValue(Attendees.ATTENDEE_TYPE, type)
				.withValue(Attendees.ATTENDEE_STATUS, status);
	}

	protected Builder buildReminder(Builder builder, VAlarm alarm) {
		int minutes = 0;

		Dur duration;
		if (alarm.getTrigger() != null
				&& (duration = alarm.getTrigger().getDuration()) != null)
			minutes = duration.getDays() * 24 * 60 + duration.getHours() * 60
					+ duration.getMinutes();

		Log.i(TAG, "Adding alarm " + minutes + " min before");

		return builder.withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
				.withValue(Reminders.MINUTES, minutes);
	}
}
