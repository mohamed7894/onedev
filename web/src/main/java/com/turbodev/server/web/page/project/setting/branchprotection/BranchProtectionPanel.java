package com.turbodev.server.web.page.project.setting.branchprotection;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.attributes.AjaxRequestAttributes;
import org.apache.wicket.ajax.markup.html.AjaxLink;
import org.apache.wicket.ajax.markup.html.form.AjaxCheckBox;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;

import com.turbodev.server.model.support.BranchProtection;
import com.turbodev.server.web.editable.BeanContext;
import com.turbodev.server.web.util.ajaxlistener.ConfirmListener;

@SuppressWarnings("serial")
abstract class BranchProtectionPanel extends Panel {

	private final BranchProtection protection;
	
	public BranchProtectionPanel(String id, BranchProtection protection) {
		super(id);
		
		this.protection = protection;
	}

	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		add(new AjaxLink<Void>("edit") {

			@Override
			public void onClick(AjaxRequestTarget target) {
				BranchProtectionEditor editor = new BranchProtectionEditor("protection", protection) {

					@Override
					protected void onSave(AjaxRequestTarget target, BranchProtection protection) {
						BranchProtectionPanel.this.onSave(target, protection);
						Component protectionViewer = BeanContext.viewBean("protection", protection).setOutputMarkupId(true);
						BranchProtectionPanel.this.replace(protectionViewer);
						target.add(protectionViewer);
					}

					@Override
					protected void onCancel(AjaxRequestTarget target) {
						Component protectionViewer = BeanContext.viewBean("protection", protection).setOutputMarkupId(true);
						BranchProtectionPanel.this.replace(protectionViewer);
						target.add(protectionViewer);
					}
					
				};
				BranchProtectionPanel.this.replace(editor);
				target.add(editor);
			}
			
		});
		
		add(new AjaxLink<Void>("delete") {

			@Override
			protected void updateAjaxAttributes(AjaxRequestAttributes attributes) {
				super.updateAjaxAttributes(attributes);
				attributes.getAjaxCallListeners().add(new ConfirmListener("Do you really want to delete this protection?"));
			}

			@Override
			public void onClick(AjaxRequestTarget target) {
				onDelete(target);
			}
			
		});
		
		add(new AjaxCheckBox("enable", Model.of(protection.isEnabled())) {
			
			@Override
			protected void onUpdate(AjaxRequestTarget target) {
				protection.setEnabled(!protection.isEnabled());
				onSave(target, protection);
				target.add(BranchProtectionPanel.this);
			}
			
		});
		
		add(BeanContext.viewBean("protection", protection).setOutputMarkupId(true));
		
		add(AttributeAppender.append("class", new LoadableDetachableModel<String>() {

			@Override
			protected String load() {
				return !protection.isEnabled()? "disabled": "";
			}
			
		}));
		
		setOutputMarkupId(true);
	}
	
	protected abstract void onDelete(AjaxRequestTarget target);

	protected abstract void onSave(AjaxRequestTarget target, BranchProtection protection);
	
}