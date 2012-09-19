/*
 * Zed Attack Proxy (ZAP) and its related class files.
 * 
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0 
 *   
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License. 
 */
package org.zaproxy.zap.extension.httppanel.component;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.MutableComboBoxModel;

import org.apache.commons.configuration.FileConfiguration;
import org.apache.log4j.Logger;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelDefaultViewSelector;
import org.zaproxy.zap.extension.httppanel.view.HttpPanelView;
import org.zaproxy.zap.extension.search.SearchMatch;
import org.zaproxy.zap.extension.search.SearchableHttpPanelView;
import org.zaproxy.zap.utils.SortedComboBoxModel;

public class HttpPanelComponentViewsManager implements ItemListener {

	protected static final Logger log = Logger.getLogger(HttpPanelComponentViewsManager.class);
	
	private HttpMessage httpMessage;
	
	protected JPanel panelViews;
	protected JComboBox comboBoxSelectView;
	protected HttpPanelView currentView;
	protected List<ViewItem> enabledViews;
	protected Map<String, ViewItem> viewItems;
	protected Map<String, HttpPanelView> views;
	protected List<HttpPanelDefaultViewSelector> defaultViewsSelectors;
	protected String savedSelectedViewName;
	
	private static DefaultViewSelectorComparator defaultViewSelectorComparator;

	private static final String VIEWS_KEY = "views";
	private static final String DEFAULT_VIEW_KEY = "defaultview";
	private String configurationKey;
	private String viewsConfigurationKey;
	
	boolean isEditable;
	
	private Object changingComboBoxLocker;
	private boolean changingComboBox;
	
	public HttpPanelComponentViewsManager(String configurationKey) {
		enabledViews = new ArrayList<ViewItem>();
		viewItems = new HashMap<String, ViewItem>();
		views = new HashMap<String, HttpPanelView>();
		defaultViewsSelectors = new ArrayList<HttpPanelDefaultViewSelector>();
		
		isEditable = false;
		this.configurationKey = configurationKey;
		this.viewsConfigurationKey = "";
		
		changingComboBoxLocker = new Object();
		changingComboBox = false;
		
		savedSelectedViewName = null;

		comboBoxSelectView = new JComboBox(new SortedComboBoxModel());
		comboBoxSelectView.addItemListener(this);

		panelViews = new JPanel(new CardLayout());
	}
	
	public HttpPanelComponentViewsManager(String configurationKey, String label) {
		this(configurationKey);

		comboBoxSelectView.setRenderer(new CustomDelegateListCellRenderer(comboBoxSelectView, label));
	}
	
	private static final class CustomDelegateListCellRenderer implements ListCellRenderer {
		
		private ListCellRenderer delegateRenderer;
		private JComboBox comboBox;
		private String label;
		
		public CustomDelegateListCellRenderer(JComboBox comboBox, String label) {
			this.delegateRenderer = comboBox.getRenderer();
			this.comboBox = comboBox;
			this.label = label;
			
			this.comboBox.addPropertyChangeListener("UI", new PropertyChangeListener() {
				
				@Override
				public void propertyChange(PropertyChangeEvent evt) {
					delegateRenderer = new JComboBox().getRenderer();
				}
			});
		}
		
		@Override
		public Component getListCellRendererComponent(JList list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
			if (index != -1) {
				return delegateRenderer.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
			}
			
			return delegateRenderer.getListCellRendererComponent(list, label + value.toString(), index, isSelected, cellHasFocus);
		}
	}
	
	public JComboBox getComboBox() {
		return comboBoxSelectView;
	}
	
	public JPanel getViewsPanel() {
		return panelViews;
	}
	
	public void setSelected(boolean selected) {
		currentView.setSelected(selected);
	}
	
	public void switchView(String name) {
		HttpPanelView view = views.get(name);
		
		if (view == null) {
			log.error("Could not find view: " + name);
			return;
		}
		
		if (this.currentView != null) {
			this.currentView.setSelected(false);
			this.currentView.getModel().clear();
		}
		
		this.currentView = view;
		
		ViewItem selectedItem = (ViewItem)comboBoxSelectView.getSelectedItem();
		if (selectedItem != null) {
			final String currentViewConfigName = currentView.getConfigName();
			final String selectedItemConfigName = selectedItem.getConfigName();
			if (!currentViewConfigName.equals(selectedItemConfigName)) {
				comboBoxSelectView.setSelectedItem(viewItems.get(currentViewConfigName));
			}
		} else {
			comboBoxSelectView.setSelectedItem(viewItems.get(this.currentView.getConfigName()));
		}
		
		this.currentView.getModel().setHttpMessage(httpMessage);
		
		CardLayout card = (CardLayout) panelViews.getLayout();
		card.show(panelViews, name);

		this.currentView.setSelected(true);
	}
	
	public void setHttpMessage(HttpMessage httpMessage) {
		this.httpMessage = httpMessage;
		
		synchronized (changingComboBoxLocker) {
			changingComboBox = true;
		}

		Iterator<Entry<String, HttpPanelView>> it = views.entrySet().iterator();
		while (it.hasNext()) {
			HttpPanelView view = it.next().getValue();
			
			ViewItem viewItem = viewItems.get(view.getConfigName());
			
			if (!view.isEnabled(httpMessage)) {
				if (enabledViews.contains(viewItem)) {
					enabledViews.remove(viewItem);
					((MutableComboBoxModel)comboBoxSelectView.getModel()).removeElement(viewItem);
				}
			} else if (!enabledViews.contains(viewItem)) {
				enabledViews.add(viewItem);
				Collections.sort(enabledViews);
				((MutableComboBoxModel)comboBoxSelectView.getModel()).addElement(viewItem);
			}
		}
		
		boolean switchView = false;
		String viewName = currentView.getConfigName();
		
		if (!enabledViews.contains(viewItems.get(viewName))) {
			switchView = true;
			viewName = null;
		}
		
		Iterator<HttpPanelDefaultViewSelector> itD = defaultViewsSelectors.iterator();
		while (itD.hasNext()) {
			HttpPanelDefaultViewSelector defaultView = itD.next();
			
			if (defaultView.matchToDefaultView(httpMessage)) {
				if (enabledViews.contains(viewItems.get(defaultView.getViewName()))) {
					if (!currentView.getConfigName().equals(defaultView.getViewName())) {
						switchView = true;
						viewName = defaultView.getViewName();
					}
					break;
				}
			}
		}
		
		if (switchView) {
			if (viewName == null) {
				viewName = enabledViews.get(0).getConfigName();
			}
			switchView(viewName);
		} else {
			currentView.getModel().setHttpMessage(httpMessage);
		}
		
		synchronized (changingComboBoxLocker) {
			changingComboBox = false;
		}
	}
	
	@Override
	public void itemStateChanged(ItemEvent e) {
		
		synchronized (changingComboBoxLocker) {
			if (changingComboBox) {
				return;
			}
		}
		
		if (e.getStateChange() == ItemEvent.SELECTED) {
			if (currentView == null) {
				return;
			}
			
			ViewItem item = (ViewItem) comboBoxSelectView.getSelectedItem();
			
			if (item == null || item.getConfigName().equals(currentView.getConfigName())) {
				return;
			}
			
			save();
			
			switchView(item.getConfigName());
		}
	}
	
	public void save() {
		if (httpMessage == null || currentView == null) {
			return;
		}

		if (isEditable) {
			if (currentView.hasChanged()) {
				currentView.save();
			}
		}
	}
	
	public void addView(HttpPanelView view) {
		final String viewConfigName = view.getConfigName();
		if (views.containsKey(viewConfigName)) {
			removeView(viewConfigName);
		}
		
		views.put(viewConfigName, view);
		
		panelViews.add(view.getPane(), viewConfigName);
		
		ViewItem viewItem = new ViewItem(viewConfigName, view.getName(), view.getPosition());
		viewItems.put(viewConfigName, viewItem);
		
		if (view.isEnabled(httpMessage)) {
			synchronized (changingComboBoxLocker) {
				changingComboBox = true;
				
				comboBoxSelectView.addItem(viewItem);
				
				changingComboBox = false;
			}
			enabledViews.add(viewItem);
			Collections.sort(enabledViews);
		}
		
		if (savedSelectedViewName != null) {
			if (savedSelectedViewName.equals(viewConfigName)) {
				switchView(viewConfigName);
			}
		}
		
		if (view.isEnabled(httpMessage)) {
			if (currentView == null) {
				switchView(viewConfigName);
			} else if (savedSelectedViewName == null && currentView.getPosition() > view.getPosition()) {
				switchView(viewConfigName);
			}
		}
		
		view.setEditable(isEditable);
		view.setParentConfigurationKey(viewsConfigurationKey);
	}
	
	public void addView(HttpPanelView view, FileConfiguration fileConfiguration) {
		addView(view);
		
		view.loadConfiguration(fileConfiguration);
	}
	
	public void removeView(String viewName) {
		HttpPanelView view = views.get(viewName);
		if (view == null) {
			return ;
		}
		
		views.remove(viewName);
		
		panelViews.remove(view.getPane());
		
		final String viewConfigName = view.getConfigName();
		
		ViewItem viewItem = viewItems.get(viewConfigName);
		if (enabledViews.contains(viewItem)) {
			synchronized (changingComboBoxLocker) {
				changingComboBox = true;
				
				comboBoxSelectView.removeItem(viewItem);
				
				changingComboBox = false;
			}
			enabledViews.remove(viewItem);
		}
		
		viewItems.remove(view.getConfigName());
		
		if (viewConfigName.equals(currentView.getConfigName())) {
			if (enabledViews.size() > 0) {
				switchView(enabledViews.get(0).getConfigName());
			} else {
				currentView = null;
			}
		}
	}
	
	public void clearView() {
		currentView.getModel().clear();
		setHttpMessage(null);
	}
	
	public void clearView(boolean enableViewSelect) {
		clearView();
		setEnableViewSelect(enableViewSelect);
	}
	
	public void setEnableViewSelect(boolean enableViewSelect) {
		comboBoxSelectView.setEnabled(enableViewSelect);
	}
	
	public void addDefaultViewSelector(HttpPanelDefaultViewSelector defaultViewSelector) {
		defaultViewsSelectors.add(defaultViewSelector);
		Collections.sort(defaultViewsSelectors, getDefaultViewSelectorComparator());
	}
	
	private static Comparator<HttpPanelDefaultViewSelector> getDefaultViewSelectorComparator() {
		if (defaultViewSelectorComparator == null) {
			createDefaultViewSelectorComparator();
		}
		return defaultViewSelectorComparator;
	}
	
	private static synchronized void createDefaultViewSelectorComparator() {
		if (defaultViewSelectorComparator == null) {
			defaultViewSelectorComparator = new DefaultViewSelectorComparator();
		}
	}
	
	public void removeDefaultViewSelector(String defaultViewSelectorName) {
		Iterator<HttpPanelDefaultViewSelector> itD = defaultViewsSelectors.iterator();
		while (itD.hasNext()) {
			HttpPanelDefaultViewSelector defaultView = itD.next();
			
			if (defaultView.getName().equals(defaultViewSelectorName)) {
				defaultViewsSelectors.remove(defaultView);
				break;
			}
		}
	}

	public void setConfigurationKey(String parentKey) {
		configurationKey = parentKey + configurationKey + ".";
		viewsConfigurationKey = configurationKey + VIEWS_KEY + ".";
		
		Iterator<HttpPanelView> it = views.values().iterator();
		while (it.hasNext()) {
			it.next().setParentConfigurationKey(viewsConfigurationKey);
		}
	}
	
	public void loadConfig(FileConfiguration fileConfiguration) {
		savedSelectedViewName = fileConfiguration.getString(configurationKey + DEFAULT_VIEW_KEY);
		
		Iterator<HttpPanelView> it = views.values().iterator();
		while (it.hasNext()) {
			it.next().loadConfiguration(fileConfiguration);
		}
	}

	public void saveConfig(FileConfiguration fileConfiguration) {
		if (currentView != null) {
			fileConfiguration.setProperty(configurationKey + DEFAULT_VIEW_KEY, currentView.getConfigName());
		}
		
		Iterator<HttpPanelView> it = views.values().iterator();
		while (it.hasNext()) {
			it.next().saveConfiguration(fileConfiguration);
		}
	}
	
	public void setEditable(boolean editable) {
		if (isEditable != editable) {
			isEditable = editable;
			
			Iterator<HttpPanelView> it = views.values().iterator();
			while (it.hasNext()) {
				it.next().setEditable(editable);
			}
		}
	}
	
	public void highlight(SearchMatch sm) {
		if (currentView instanceof SearchableHttpPanelView) {
			((SearchableHttpPanelView)currentView).highlight(sm);
		} else {
			SearchableHttpPanelView searchableView = findSearchableView();
			if (currentView != null) {
				switchView(((HttpPanelView)searchableView).getConfigName());
				searchableView.highlight(sm);
			}
		}
	}
	
	public void search(Pattern p, List<SearchMatch> matches) {
		if (currentView instanceof SearchableHttpPanelView) {
			((SearchableHttpPanelView)currentView).search(p, matches);
		} else {
			SearchableHttpPanelView searchableView = findSearchableView();
			if (searchableView != null) {
				searchableView.search(p, matches);
			}
		}
	}
	
	private SearchableHttpPanelView findSearchableView() {
		SearchableHttpPanelView searchableView = null;
		
		Iterator<HttpPanelView> it = views.values().iterator();
		while (it.hasNext()) {
			HttpPanelView view = it.next();
			if (view.isEnabled(httpMessage)) {
				if (view instanceof SearchableHttpPanelView) {
					searchableView = (SearchableHttpPanelView)view;
					break;
				}
			}
		}
		
		return searchableView;
	}
	
	private static final class ViewItem implements Comparable<ViewItem> {
		
		private final String configName;
		private final String name;
		private final int position;
		
		public ViewItem(String configName, String name, int position) {
			this.configName = configName;
			this.name = name;
			this.position = position;
		}
		
		public String getConfigName() {
			return configName;
		}
		
		@Override
		public int compareTo(ViewItem o) {
			if (position < o.position) {
				return -1;
			} else if (position > o.position) {
				return 1;
			}
			
			return 0;
		}
		
		@Override
		public int hashCode() {
			return 31 * configName.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			ViewItem other = (ViewItem) obj;
			if (!configName.equals(other.configName)) {
				return false;
			}
			return true;
		}

		@Override
		public String toString() {
			return name;
		}
	}
	
	private static final class DefaultViewSelectorComparator implements Comparator<HttpPanelDefaultViewSelector>, Serializable {

		private static final long serialVersionUID = -1380844848294384189L;

		@Override
		public int compare(HttpPanelDefaultViewSelector o1, HttpPanelDefaultViewSelector o2) {
			final int order1 = o1.getOrder();
			final int order2 = o2.getOrder(); 
			if (order1 < order2) {
				return -1;
			} else if (order1 > order2) {
				return 1;
			}
			return 0;
		}
	}
}
