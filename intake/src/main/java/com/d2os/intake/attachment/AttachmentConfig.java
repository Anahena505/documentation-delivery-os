package com.d2os.intake.attachment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the attachment upload-surface policy binding (T003/T040). */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentConfig {}
