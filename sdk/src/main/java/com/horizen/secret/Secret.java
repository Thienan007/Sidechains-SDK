package com.horizen.secret;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.horizen.proof.ProofOfKnowledge;
import com.horizen.proposition.ProofOfKnowledgeProposition;
import com.horizen.serialization.Views;

@JsonView(Views.Default.class)
@JsonIgnoreProperties({"secretTypeId", "publicImage", "serializer", "sign", "owns"})
public interface Secret
    extends scorex.core.serialization.BytesSerializable
{
    byte secretTypeId();

    ProofOfKnowledgeProposition publicImage();

    @Override
    @JsonProperty("bytes")
    byte[] bytes();

    @Override
    SecretSerializer serializer();

    boolean owns(ProofOfKnowledgeProposition proposition);

    ProofOfKnowledge sign(byte[] message);

    @JsonProperty("typeName")
    default String typeName() {
        return this.getClass().getSimpleName();
    }

    @JsonProperty("isCustom")
    default Boolean isCustom() { return true; } // All secrets presume customs until it not defined otherwise
}
