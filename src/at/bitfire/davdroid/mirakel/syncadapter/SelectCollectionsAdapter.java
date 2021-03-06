/*******************************************************************************
 * Copyright (c) 2014 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Richard Hirner (bitfire web engineering) - initial API and implementation
 ******************************************************************************/
package at.bitfire.davdroid.mirakel.syncadapter;

import lombok.Getter;
import android.content.Context;
import android.text.Html;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;
import at.bitfire.davdroid.mirakel.R;
import at.bitfire.davdroid.mirakel.resource.ServerInfo;

public class SelectCollectionsAdapter extends BaseAdapter implements ListAdapter {
	final static int TYPE_ADDRESS_BOOKS_HEADING = 0,
		TYPE_ADDRESS_BOOKS_ROW = 1,
		TYPE_CALENDARS_HEADING = 2,
		TYPE_CALENDARS_ROW = 3,
        TYPE_TODO_LIST_HEADING = 4,
        TYPE_TODO_LIST_ROW = 5;
    private static final String TAG = "SelectCollectionsAdapter";

    protected Context context;
	protected ServerInfo serverInfo;
	@Getter protected int nAddressBooks, nCalendars, nToDoLists;
	
	
	public SelectCollectionsAdapter(Context context, ServerInfo serverInfo) {
		this.context = context;
		
		this.serverInfo = serverInfo;
		nAddressBooks = (serverInfo.getAddressBooks() == null) ? 0 : serverInfo.getAddressBooks().size();
		nCalendars = (serverInfo.getCalendars() == null) ? 0 : serverInfo.getCalendars().size();
        nToDoLists = (serverInfo.getTodoLists() == null) ? 0 : serverInfo.getTodoLists().size();
	}
	
	
	// item data
	
	@Override
	public int getCount() {
		return nAddressBooks + nCalendars+nToDoLists + 3;
	}

	@Override
	public Object getItem(int position) {

        switch (getItemViewType(position)){
            case TYPE_ADDRESS_BOOKS_ROW:
                return serverInfo.getAddressBooks().get(position - 1);
            case TYPE_CALENDARS_ROW:
                return serverInfo.getCalendars().get(position - nAddressBooks - 2);
            case TYPE_TODO_LIST_ROW:
                return serverInfo.getTodoLists().get(position - nAddressBooks - nCalendars - 3);
            case TYPE_TODO_LIST_HEADING:
            case TYPE_ADDRESS_BOOKS_HEADING:
            case TYPE_CALENDARS_HEADING:
            default:
                Log.wtf(TAG,"unsupportet type");
        }
        Log.wtf("foo", "pos: " +position);
        Log.wtf("foo","calcount: "+nCalendars);
        Log.wtf("foo","addrcount: "+nAddressBooks);
        Log.wtf("foo","todocount: "+nToDoLists);
		return null;
	}
	
	@Override
	public boolean hasStableIds() {
		return true;
	}

	@Override
	public long getItemId(int position) {
		return position;
	}
	
	
	// item views

	@Override
	public int getViewTypeCount() {
		return 6;
	}

	@Override
	public int getItemViewType(int position) {
		if (position == 0)
			return TYPE_ADDRESS_BOOKS_HEADING;
		else if (position <= nAddressBooks)
			return TYPE_ADDRESS_BOOKS_ROW;
		else if (position == nAddressBooks + 1)
			return TYPE_CALENDARS_HEADING;
		else if (position <= nAddressBooks + nCalendars + 1)
			return TYPE_CALENDARS_ROW;
		else if(position ==nAddressBooks + nCalendars + 2)
            return TYPE_TODO_LIST_HEADING;
        else if(position<= nAddressBooks + nCalendars +nToDoLists+2 )
            return TYPE_TODO_LIST_ROW;
        else
			return IGNORE_ITEM_VIEW_TYPE;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		// step 1: get view (either by creating or recycling)
		if (convertView == null) {
			LayoutInflater inflater = LayoutInflater.from(parent.getContext());
			switch (getItemViewType(position)) {
			case TYPE_ADDRESS_BOOKS_HEADING:
				convertView = inflater.inflate(R.layout.address_books_heading, parent, false);
				break;
			case TYPE_ADDRESS_BOOKS_ROW:
				convertView = inflater.inflate(android.R.layout.simple_list_item_single_choice, null);
				break;
			case TYPE_CALENDARS_HEADING:
				convertView = inflater.inflate(R.layout.calendars_heading, parent, false);
				break;
            case TYPE_CALENDARS_ROW:
            case TYPE_TODO_LIST_ROW:
				convertView = inflater.inflate(android.R.layout.simple_list_item_multiple_choice, null);
                break;
            case TYPE_TODO_LIST_HEADING:
                //TODO change this!!!
                convertView = inflater.inflate(R.layout.todo_list_heading, parent, false);
                break;

            }
		}
		
		// step 2: fill view with content
		switch (getItemViewType(position)) {
		case TYPE_ADDRESS_BOOKS_ROW:
			setContent((CheckedTextView)convertView, R.drawable.addressbook, (ServerInfo.ResourceInfo)getItem(position));
			break;
		case TYPE_CALENDARS_ROW:
			setContent((CheckedTextView)convertView, R.drawable.calendar, (ServerInfo.ResourceInfo)getItem(position));
            break;
        case TYPE_TODO_LIST_ROW:
            setContent((CheckedTextView)convertView, R.drawable.calendar, (ServerInfo.ResourceInfo)getItem(position));
            break;
		}
		
		return convertView;
	}
	
	protected void setContent(CheckedTextView view, int collectionIcon, ServerInfo.ResourceInfo info) {
		// set layout and icons
		view.setPadding(10, 10, 10, 10);
		view.setCompoundDrawablesWithIntrinsicBounds(collectionIcon, 0, info.isReadOnly() ? R.drawable.ic_read_only : 0, 0);
		view.setCompoundDrawablePadding(10);
		
		// set text		
		String title = "<b>" + info.getTitle() + "</b>";
		if (info.isReadOnly())
			title = title + " (" + context.getString(R.string.read_only) + ")";
		
		String description = info.getDescription();
		if (description == null)
			description = info.getURL();
		
		// FIXME escape HTML
		view.setText(Html.fromHtml(title + "<br/>" + description));
	}

	@Override
	public boolean areAllItemsEnabled() {
		return false;
	}

	@Override
	public boolean isEnabled(int position) {
		int type = getItemViewType(position);
		return (type == TYPE_ADDRESS_BOOKS_ROW || type == TYPE_CALENDARS_ROW||type==TYPE_TODO_LIST_ROW);
	}
}
