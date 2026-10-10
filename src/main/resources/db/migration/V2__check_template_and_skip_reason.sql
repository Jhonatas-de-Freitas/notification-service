-- channel and status already had a check. These two are enums stored as text as well.
alter table notifications
    add constraint notifications_template_check
        check (template in ('ORDER_RECEIVED', 'ORDER_CONFIRMED', 'ORDER_CANCELLED', 'GENERIC')),
    add constraint notifications_skip_reason_check
        check (skip_reason in ('CHANNEL_DISABLED', 'MISSING_DESTINATION'));
