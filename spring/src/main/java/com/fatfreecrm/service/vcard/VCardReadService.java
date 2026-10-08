package com.fatfreecrm.service.vcard;

import com.fatfreecrm.domain.Account;
import com.fatfreecrm.domain.Address;
import com.fatfreecrm.domain.Contact;
import com.fatfreecrm.domain.Lead;
import com.fatfreecrm.domain.support.RailsModelType;
import com.fatfreecrm.repository.AccountContactRepository;
import com.fatfreecrm.repository.AccountRepository;
import com.fatfreecrm.repository.AddressRepository;
import com.fatfreecrm.repository.ContactRepository;
import com.fatfreecrm.repository.LeadRepository;
import com.fatfreecrm.security.AuthenticatedUser;
import com.fatfreecrm.service.json.RailsResource;
import com.fatfreecrm.service.json.RailsResources;
import com.fatfreecrm.service.read.RecentlyViewedService;
import jakarta.persistence.EntityNotFoundException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VCardReadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VCardReadService.class);

    private final ContactRepository contactRepository;
    private final LeadRepository leadRepository;
    private final AccountContactRepository accountContactRepository;
    private final AccountRepository accountRepository;
    private final AddressRepository addressRepository;
    private final VCardWriter vCardWriter;
    private final RecentlyViewedService recentlyViewedService;
    private final RailsResource contactResource;
    private final RailsResource leadResource;

    public VCardReadService(
        ContactRepository contactRepository,
        LeadRepository leadRepository,
        AccountContactRepository accountContactRepository,
        AccountRepository accountRepository,
        AddressRepository addressRepository,
        VCardWriter vCardWriter,
        RecentlyViewedService recentlyViewedService,
        RailsResources railsResources
    ) {
        this.contactRepository = contactRepository;
        this.leadRepository = leadRepository;
        this.accountContactRepository = accountContactRepository;
        this.accountRepository = accountRepository;
        this.addressRepository = addressRepository;
        this.vCardWriter = vCardWriter;
        this.recentlyViewedService = recentlyViewedService;
        this.contactResource = railsResources.contact;
        this.leadResource = railsResources.lead;
    }

    @Transactional(readOnly = true)
    public VCardResponse contactVCard(AuthenticatedUser user, long id) {
        Contact contact = contactRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Contact with id " + id + " was not found"));
        List<Long> accountIds = accountContactRepository.findAccountIdsByContactId(id);
        Account account = accountIds.isEmpty() ? null : accountRepository.findById(accountIds.getFirst()).orElse(null);
        Address address = addressRepository.findByAddressableTypeAndAddressableId(
                RailsModelType.CONTACT, Math.toIntExact(id)).stream()
            .filter(candidate -> "Business".equals(candidate.getAddressType()))
            .min(Comparator.comparing(Address::getId))
            .orElse(null);
        VCardWriter.AddressData addressData = address == null ? null : new VCardWriter.AddressData(
            address.getStreet1(), address.getStreet2(), address.getCity(), address.getState(),
            address.getZipcode(), address.getCountry());
        String body = vCardWriter.write(new VCardWriter.ContactData(
            contact.getFirstName(), contact.getLastName(), contact.getTitle(),
            account == null ? null : account.getName(), contact.getDepartment(),
            contact.getEmail(), contact.getAltEmail(), contact.getMobile(), contact.getPhone(), addressData
        ));
        recordView(user, contactResource, id);
        return new VCardResponse(body, vCardWriter.contentDisposition(contact.getFirstName(), contact.getLastName()));
    }

    @Transactional(readOnly = true)
    public VCardResponse leadVCard(AuthenticatedUser user, long id) {
        Lead lead = leadRepository.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Lead with id " + id + " was not found"));
        String body = vCardWriter.write(new VCardWriter.LeadData(
            lead.getFirstName(), lead.getLastName(), lead.getTitle(), lead.getCompany(),
            lead.getEmail(), lead.getAltEmail(), lead.getMobile(), lead.getPhone()
        ));
        recordView(user, leadResource, id);
        return new VCardResponse(body, vCardWriter.contentDisposition(lead.getFirstName(), lead.getLastName()));
    }

    private void recordView(AuthenticatedUser user, RailsResource resource, long id) {
        try {
            recentlyViewedService.recordView(user, resource, id);
        } catch (RuntimeException exception) {
            LOGGER.warn("Unable to record recently viewed {} {}", resource.railsModel(), id, exception);
        }
    }

}
